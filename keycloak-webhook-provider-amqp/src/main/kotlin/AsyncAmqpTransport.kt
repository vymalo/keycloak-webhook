package com.vymalo.keycloak.webhook

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ShutdownSignalException
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Publishes in the background (WEBHOOK_AMQP_PUBLISH_MODE=async), so a login never waits
 * for RabbitMQ and events survive a broker outage in memory.
 *
 * [publish] only drops the message into a [DropOldestBuffer]. One daemon thread owns the
 * connection: it connects (with backoff), sends, and after any failure reconnects and sends
 * again whatever the broker hadn't confirmed. All broker state lives on that thread; the
 * only things shared with others are the buffer and the counters.
 *
 * With publisher confirms on, delivery is at least once: every message waits in a
 * [ConfirmTracker] (one per channel) until the broker confirms it. If the oldest one waits
 * longer than the confirm timeout, the connection is replaced and the unconfirmed messages
 * are sent again. A message the broker refuses is retried a few times, then dropped.
 * Without confirms, a message is done once written to the socket, as in sync mode.
 */
class AsyncAmqpTransport(private val config: AmqpConfig) : Transport {

    // Recovery is ours: the client's automatic recovery would bring back channels whose
    // sequence numbers no longer match the tracker. Timeouts keep a dead broker from
    // parking the publisher thread for the client's default of minutes.
    private val connections = AmqpConnections(config) {
        isAutomaticRecoveryEnabled = false
        isTopologyRecoveryEnabled = false
        connectionTimeout = BROKER_TIMEOUT_MS
        handshakeTimeout = BROKER_TIMEOUT_MS
        channelRpcTimeout = BROKER_TIMEOUT_MS
    }
    private val baseProperties = AmqpMessage.properties(config)
    private val buffer = DropOldestBuffer<AmqpMessage>(config.bufferCapacity)

    /** What happened so far. Read by tests to see *how* delivery went, not just whether it did. */
    internal class Stats {
        /** Connections opened, the first one included. */
        val connects = AtomicLong()
        /** Connections replaced because the oldest message waited too long for its confirm. */
        val watchdogRecycles = AtomicLong()
        /** Messages sent again after their connection was lost or replaced. */
        val resent = AtomicLong()
        /** Messages the broker refused so often that they were dropped. */
        val refusedDropped = AtomicLong()
        /** Messages lost to a full buffer, oldest first. */
        val overflowDropped = AtomicLong()
    }

    internal val stats = Stats()

    @Volatile
    private var accepting = true

    /** System.nanoTime() after which a closing transport stops trying to deliver what's left. */
    @Volatile
    private var drainUntilNanos = Long.MAX_VALUE

    // Owned by the publisher thread alone.
    private var connection: Connection? = null
    private var channel: Channel? = null
    private var tracker: ConfirmTracker<AmqpMessage>? = null

    // Started last, once every field it reads exists.
    private val publisher = thread(name = "keycloak-webhook-amqp-publisher", isDaemon = true) { runPublisher() }

    /** Never blocks: at worst the buffer drops its oldest message to make room. */
    override fun publish(payload: WebhookPayload) {
        if (!accepting) {
            logger.warn("AMQP publisher is shut down; dropping webhook: {}", payload)
            return
        }
        buffer.add(AmqpMessage.of(payload, config, baseProperties))?.let { countOverflow() }
    }

    /**
     * Stops taking events and gives the publisher up to [DRAIN_MS] to deliver (and, with
     * confirms, get confirmed) what's buffered. Whatever is left after that is logged as lost.
     */
    override fun close() {
        accepting = false
        drainUntilNanos = System.nanoTime() + DRAIN_MS * 1_000_000
        publisher.join(DRAIN_MS + 1_000)
        if (publisher.isAlive) {
            // Most likely asleep between reconnects, or stuck on a broker that doesn't answer.
            publisher.interrupt()
            publisher.join(BROKER_TIMEOUT_MS.toLong())
        }
    }

    private fun runPublisher() {
        var backoffMs = MIN_BACKOFF_MS
        try {
            while (accepting || (hasWorkLeft() && System.nanoTime() < drainUntilNanos)) {
                try {
                    val channel = liveChannel() ?: connect().also { backoffMs = MIN_BACKOFF_MS }
                    if (confirmsStuck()) {
                        stats.watchdogRecycles.incrementAndGet()
                        recycle("the oldest message waited more than ${config.confirmTimeoutMs}ms for its confirm")
                        continue
                    }
                    sendNext(channel)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("AMQP publisher: {}. Reconnecting in {}ms", e.message ?: e.javaClass.simpleName, backoffMs)
                    recycle(null, blameUnconfirmed = e is ChannelClosedByBroker && e.causedByUs)
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        } catch (_: InterruptedException) {
            // close() gave up waiting; fall through to the cleanup.
        }
        val lost = buffer.size + (tracker?.abandon()?.size ?: 0)
        AmqpConnections.shutDown(connection, channel, BROKER_TIMEOUT_MS)
        if (lost > 0) logger.warn("AMQP publisher stopped with {} undelivered messages", lost)
        logger.info(
            "AMQP publisher stopped: {} connections, {} replaced by the watchdog, {} messages resent, " +
                "{} dropped when the buffer was full, {} dropped after repeated refusals",
            stats.connects, stats.watchdogRecycles, stats.resent, stats.overflowDropped, stats.refusedDropped,
        )
    }

    /**
     * The current channel if it's still usable, null if there is none yet. A channel that was
     * open and isn't any more is an error, so the loop backs off before reconnecting instead
     * of reconnecting in a tight loop.
     */
    private fun liveChannel(): Channel? {
        val channel = channel ?: return null
        if (channel.isOpen && connection?.isOpen == true) return channel
        throw ChannelClosedByBroker(channel.closeReason ?: connection?.closeReason)
    }

    /**
     * Sends at most one message. Every wait here is short and bounded, so the loop gets back
     * to its watchdog and shutdown checks several times a second even when nothing moves.
     */
    private fun sendNext(channel: Channel) {
        val tracker = tracker
        if (tracker != null && !tracker.reserve(POLL_MS)) return
        val message = buffer.poll(POLL_MS) ?: run { tracker?.unreserve(); return }

        val seqNo = channel.nextPublishSeqNo
        tracker?.sent(seqNo, message)
        try {
            channel.basicPublish(config.exchange, message.routingKey, message.properties, message.body)
        } catch (e: Exception) {
            // If the tracker no longer has it, a recycle already put it back in the buffer.
            if (tracker == null || tracker.forget(seqNo) != null) requeue(listOf(message))
            throw e
        }
    }

    private fun connect(): Channel {
        recycle(null)
        val connection = connections.open().also { connection = it }
        stats.connects.incrementAndGet()
        val channel = connection.createChannel().also { channel = it }
        if (config.publisherConfirm) {
            channel.confirmSelect()
            val tracker = ConfirmTracker<AmqpMessage>(config.inflightCapacity).also { tracker = it }
            // Bound to this channel's own tracker, so a late confirm from it can never touch the next one's.
            channel.addConfirmListener(
                { tag, multiple -> tracker.confirmed(tag, multiple) },
                { tag, multiple -> retryRefused(tracker.refused(tag, multiple)) },
            )
        }
        logger.info("AMQP publisher connected; {} messages waiting", buffer.size)
        return channel
    }

    private fun confirmsStuck(): Boolean = (tracker?.oldestWaitMs() ?: 0) > config.confirmTimeoutMs

    /**
     * Drops the connection and sends everything it hadn't confirmed again. A dead connection
     * or a stuck confirm isn't the messages' fault, so they go straight back to the buffer.
     * When the broker closed the channel over something we sent ([blameUnconfirmed]), they
     * count as refused instead, so a message that can never be delivered doesn't loop forever.
     */
    private fun recycle(reason: String?, blameUnconfirmed: Boolean = false) {
        if (reason != null) logger.warn("AMQP publisher: {}; replacing the connection", reason)
        val unconfirmed = tracker?.abandon().orEmpty()
        stats.resent.addAndGet(unconfirmed.size.toLong())
        if (blameUnconfirmed) retryRefused(unconfirmed) else if (unconfirmed.isNotEmpty()) requeue(unconfirmed)
        AmqpConnections.shutDown(connection, channel, BROKER_TIMEOUT_MS)
        connection = null
        channel = null
        tracker = null
    }

    /** Tries each refused message again a few times, then gives up on it. */
    private fun retryRefused(refused: List<AmqpMessage>) {
        val (retry, giveUp) = refused.map { it.refusedOnceMore() }.partition { it.refusals < MAX_REFUSALS }
        giveUp.forEach {
            logger.error("Broker refused message {} times, dropping it (routing key {})", it.refusals, it.routingKey)
            stats.refusedDropped.incrementAndGet()
        }
        if (retry.isNotEmpty()) requeue(retry)
    }

    private fun requeue(messages: List<AmqpMessage>) {
        buffer.requeue(messages).forEach { _ -> countOverflow() }
    }

    private fun hasWorkLeft() = buffer.size > 0 || tracker?.isEmpty == false

    /** Warns on the first drop and every hundredth after, so an outage can't flood the log. */
    private fun countOverflow() {
        val total = stats.overflowDropped.incrementAndGet()
        if (total == 1L || total % 100 == 0L) {
            logger.warn("AMQP buffer is full: dropped the oldest message ({} dropped so far)", total)
        }
    }

    /**
     * The broker closed our channel or connection. A channel-level error that we didn't ask
     * for (not found, access refused, ...) was caused by what we sent; anything
     * connection-level is the network or the broker itself.
     */
    private class ChannelClosedByBroker(reason: ShutdownSignalException?) :
        Exception("channel closed by the broker: ${reason?.message ?: "no reason given"}") {
        val causedByUs = reason != null && !reason.isHardError && !reason.isInitiatedByApplication
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(AsyncAmqpTransport::class.java)

        private const val POLL_MS = 100L
        private const val MIN_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 5_000L
        private const val BROKER_TIMEOUT_MS = 5_000
        private const val DRAIN_MS = 5_000L
        private const val MAX_REFUSALS = 5
    }
}
