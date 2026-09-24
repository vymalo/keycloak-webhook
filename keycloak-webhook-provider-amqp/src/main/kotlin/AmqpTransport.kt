package com.vymalo.keycloak.webhook

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException

/**
 * Publishes each event as one message to a RabbitMQ exchange, on the Keycloak request thread.
 * The default mode; [AsyncAmqpTransport] is the opt-in alternative.
 *
 * One connection and channel serve the whole server. Publishing is serialized: a
 * channel waiting for confirms must not be shared mid-flight. If the connection is
 * gone, [publish] reconnects inline (three attempts, a second apart) and otherwise
 * drops the event with a log line.
 *
 * What keeps a dead broker from piling up logins: one thread reconnects, and threads
 * that arrive meanwhile wait for its result instead of running rounds of their own.
 * After a failed round, events are dropped at once for [COOLDOWN_MS].
 */
class AmqpTransport(private val config: AmqpConfig) : Transport {

    private val connections = AmqpConnections(config) { isAutomaticRecoveryEnabled = true }

    /** Built once: identical for every message, except the message id when that is on. */
    private val baseProperties = AmqpMessage.properties(config)

    // Replaced only under `this`; volatile so a healthy channel is found without taking a lock.
    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var channel: Channel? = null

    /** Set for good by [close]: a session still running at shutdown must not reconnect and leak. */
    @Volatile
    private var closed = false

    /** Held for a whole reconnect round, so concurrent threads share one round. */
    private val reconnectLock = Any()

    /** Until when (System.nanoTime) events are dropped without trying, after a failed round. */
    @Volatile
    private var coolingDownUntil = System.nanoTime()

    override fun publish(payload: WebhookPayload) {
        val channel = openChannel() ?: run {
            logger.warn("AMQP channel or connection is still closed. Unable to send webhook: {}", payload)
            return
        }
        synchronized(this) {
            try {
                val message = AmqpMessage.of(payload, config, baseProperties)
                channel.basicPublish(config.exchange, message.routingKey, message.properties, message.body)
                if (config.publisherConfirm) channel.waitForConfirms(config.confirmTimeoutMs)
                logger.debug("Webhook message sent: {}", payload)
            } catch (e: TimeoutException) {
                logger.error(
                    "Publisher confirm timeout after {}ms — message delivery could not be verified, request: {}",
                    config.confirmTimeoutMs, payload, e,
                )
            } catch (e: Exception) {
                logger.error("Failed to send webhook message", e)
            }
        }
    }

    /** The open channel, reconnecting when needed; null if the broker stays unreachable. */
    private fun openChannel(): Channel? {
        healthyChannel()?.let { return it }
        if (closed || coolingDown()) return null
        synchronized(reconnectLock) {
            // The thread before us may have just settled it, either way.
            healthyChannel()?.let { return it }
            if (closed || coolingDown()) return null
            return reconnect()
        }
    }

    private fun healthyChannel(): Channel? =
        channel?.takeIf { !closed && it.isOpen && connection?.isOpen == true }

    private fun coolingDown() = System.nanoTime() - coolingDownUntil < 0

    private fun reconnect(): Channel? {
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            try {
                return connect()
            } catch (e: Exception) {
                logger.warn("Attempt {} to (re)connect to AMQP failed: {}", attempt, e.message, e)
                if (attempt < RECONNECT_ATTEMPTS && !pause()) return null
            }
        }
        coolingDownUntil = System.nanoTime() + COOLDOWN_MS * 1_000_000
        logger.error(
            "Unable to re-establish connection after {} attempts. Events are dropped for the next {}ms.",
            RECONNECT_ATTEMPTS, COOLDOWN_MS,
        )
        return null
    }

    /** The pause between attempts. False if interrupted: the round stops, and the interrupt is kept for Keycloak. */
    private fun pause(): Boolean = try {
        Thread.sleep(RECONNECT_DELAY_MS)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Fresh connection and channel, or null if [close] got there first. Confirm mode is
     * per channel, so it is switched on again every time; forgetting that on reconnect
     * would make every later confirm wait fail. Connecting happens outside `this`, so a
     * slow broker can't hold up [close].
     */
    private fun connect(): Channel? {
        synchronized(this) { closeQuietly() }
        val connection = connections.open()
        val channel = try {
            connection.createChannel().also { if (config.publisherConfirm) it.confirmSelect() }
        } catch (e: Exception) {
            connection.abort()
            throw e
        }
        synchronized(this) {
            if (closed) {
                connection.abort()
                return null
            }
            this.connection = connection
            this.channel = channel
        }
        return channel
    }

    override fun close() {
        closed = true
        synchronized(this) { closeQuietly() }
    }

    private fun closeQuietly() {
        AmqpConnections.shutDown(connection, channel)
        channel = null
        connection = null
    }

    private companion object {
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1_000L

        /** How long events are dropped without trying after a failed round, so a dead broker costs one round per window. */
        private const val COOLDOWN_MS = 5_000L

        private val logger = LoggerFactory.getLogger(AmqpTransport::class.java)
    }
}
