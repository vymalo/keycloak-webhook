package com.vymalo.keycloak.webhook

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.webhook.helper.amqpSsl
import com.vymalo.keycloak.webhook.helper.amqpSslTruststoreKey
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.keycloak.utils.MediaType
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Publishes each event as one message to a RabbitMQ exchange.
 *
 * One connection and channel serve the whole server. Publishing is serialized: a
 * channel waiting for confirms must not be shared mid-flight. If the connection is
 * gone, [publish] reconnects inline (three attempts, a second apart) and otherwise
 * drops the event with a log line. That blocking retry is the existing behaviour; a
 * non-blocking mode comes later as an opt-in.
 *
 * What keeps a dead broker from piling up logins: one thread reconnects, and threads
 * that arrive meanwhile wait for its result instead of running rounds of their own.
 * After a failed round, events are dropped at once for [COOLDOWN_MS].
 */
class AmqpTransport(private val config: AmqpConfig) : Transport {

    /** Internal so tests can check the settings without a broker; nothing connects until the first event. */
    internal val connectionFactory = ConnectionFactory().apply {
        username = config.username
        password = config.password
        virtualHost = config.vHost
        isAutomaticRecoveryEnabled = true
        config.heartbeatSeconds?.let { requestedHeartbeat = it }
        config.connectionTimeoutMs?.let { connectionTimeout = it }
        when {
            config.truststore != null -> {
                useSslProtocol(sslContext(config.truststore))
                enableHostnameVerification()
            }
            config.ssl -> {
                useSslProtocol()
                logger.warn(
                    "{} is on without {}: the broker's certificate is not verified",
                    amqpSsl, amqpSslTruststoreKey,
                )
            }
        }
    }

    /** Built once: identical for every message, except the message id when that is on. */
    private val baseProperties = messageProperties(config)

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
                channel.basicPublish(
                    config.exchange,
                    routingKey(payload),
                    if (config.messageId) baseProperties.builder().messageId(UUID.randomUUID().toString()).build()
                    else baseProperties,
                    gson.toJson(payload).toByteArray(Charsets.UTF_8),
                )
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
        val connection = connectionFactory.newConnection(config.addresses)
        if (config.declareExchange) declareExchange(connection)
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

    /**
     * Declares the exchange on a throwaway channel: if it already exists with other
     * settings, the broker closes the channel it was asked on, and that must not be
     * the one we publish on. That case is only logged; publishing uses it as it is.
     */
    private fun declareExchange(connection: Connection) {
        if (config.exchange.isEmpty()) return // the default exchange always exists and can't be declared
        runCatching { connection.createChannel().use { it.exchangeDeclare(config.exchange, "topic", true) } }
            .onSuccess { logger.debug("Exchange '{}' declared (durable topic)", config.exchange) }
            .onFailure {
                logger.warn("Could not declare exchange '{}', publishing to it as it is: {}", config.exchange, it.message)
            }
    }

    override fun close() {
        closed = true
        synchronized(this) { closeQuietly() }
    }

    /**
     * A dropped connection must still be shut down: with automatic recovery on, it reports
     * closed but keeps reconnecting in the background, and would come back as a leaked
     * second connection. abort() stops that and never throws; close() is kept for open
     * connections so pending frames are flushed.
     */
    private fun closeQuietly() {
        runCatching { channel?.takeIf { it.isOpen }?.close() }
            .onFailure { logger.warn("Error closing channel", it) }
        connection?.let { connection ->
            if (connection.isOpen) {
                runCatching { connection.close() }.onFailure { logger.warn("Error closing connection", it) }
            } else {
                connection.abort()
            }
        }
        channel = null
        connection = null
    }

    companion object {
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1_000L

        /** How long events are dropped without trying after a failed round, so a dead broker costs one round per window. */
        private const val COOLDOWN_MS = 5_000L

        private val gson = Gson()
        private val logger = LoggerFactory.getLogger(AmqpTransport::class.java)

        /**
         * `__TypeId__` is the header Spring AMQP reads to pick the target class, so Spring
         * consumers can deserialize without extra mapping.
         */
        internal fun messageProperties(config: AmqpConfig): BasicProperties = BasicProperties.Builder()
            .appId("Keycloak/Kotlin")
            .headers(mapOf<String, Any>("__TypeId__" to WebhookPayload::class.java.name))
            .contentType(MediaType.APPLICATION_JSON)
            .contentEncoding("UTF-8")
            .apply { if (config.persistent) deliveryMode(2) }
            .build()

        /** Trusts exactly the certificates in the configured store; a bad path or password is a setup error, logged once. */
        private fun sslContext(truststore: AmqpConfig.Truststore): SSLContext {
            val store = try {
                KeyStore.getInstance(truststore.type).apply {
                    File(truststore.path).inputStream().use { load(it, truststore.password?.toCharArray()) }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Could not load $amqpSslTruststoreKey '${truststore.path}': ${e.message}", e)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }

        /** `KC_CLIENT.<realm>.<client>.<user>.<type>` so consumers can bind on any part, e.g. `KC_CLIENT.*.*.*.LOGIN`. */
        internal fun routingKey(payload: WebhookPayload) =
            "KC_CLIENT.${payload.realmId}.${payload.clientId ?: "xxx"}.${payload.userId ?: "xxx"}.${payload.type}"
    }
}
