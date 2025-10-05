package com.vymalo.keycloak.webhook

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.BlockedListener
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.ShutdownListener
import com.rabbitmq.client.ShutdownSignalException
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.keycloak.utils.MediaType
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/**
 * Self-healing AMQP webhook handler with a bounded LRU buffer + async publisher confirms + watchdog.
 * - Request thread enqueues (non-blocking) into a capacity-limited deque.
 * - Background publisher thread drains the queue and handles reconnects.
 * - Async confirms with an in-flight window (default 5k) provide at-least-once semantics with high throughput.
 * - Confirm-timeout watchdog recycles the channel if the oldest unconfirmed publish exceeds a threshold.
 */
class AmqpWebhookHandler : WebhookHandler {

    override fun getId(): String = PROVIDER_ID

    override fun initHandler() = Shared.initOnce()

    override fun close() {
        // Intentionally no-op; wire Shared.shutdown() from your factory/lifecycle if needed.
    }

    override fun sendWebhook(request: WebhookPayload) {
        if (!Shared.initialized.get()) Shared.initOnce()

        val body = gson.toJson(request).toByteArray(StandardCharsets.UTF_8)
        val routingKey = genRoutingKey(request)
        val props = getMessageProps(request.javaClass.name)

        val ok = Shared.enqueue(PublishTask(Shared.exchange ?: "", routingKey, props, body))
        if (!ok) {
            logger.error("AMQP buffer rejected message even after LRU drop; message lost. rk={}", routingKey)
        }
    }

    companion object {
        const val PROVIDER_ID = "webhook-amqp"

        private val BACKOFF_INITIAL = 250L
        private val BACKOFF_MAX = 2_000L
        private const val THROTTLE_PARK_NS = 250_000L   // ~0.25ms
        private const val ON_ERROR_PARK_NS = 200_000L   // ~0.2ms

        private val gson = Gson()
        private val logger = LoggerFactory.getLogger(AmqpWebhookHandler::class.java)

        private fun getMessageProps(className: String): BasicProperties {
            val headers: MutableMap<String, Any> = HashMap()
            headers["__TypeId__"] = className
            return BasicProperties.Builder()
                .appId("Keycloak/Kotlin")
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .contentEncoding("UTF-8")
                .deliveryMode(2) // persistent
                .messageId(UUID.randomUUID().toString()) // for downstream idempotency
                .build()
        }

        private fun genRoutingKey(request: WebhookPayload): String =
            "KC_CLIENT.${request.realmId}.${request.clientId ?: "xxx"}.${request.userId ?: "xxx"}.${request.type}"

        // ---- Shared, process-wide publisher & AMQP state ----
        private object Shared {
            @Volatile var connection: Connection? = null
            @Volatile var channel: Channel? = null
            @Volatile var exchange: String? = null
            @Volatile var factory: ConnectionFactory? = null
            @Volatile var config: AmqpConfig? = null

            // Buffer capacity: keep the last N messages; on overflow, drop oldest.
            private lateinit var queue: LinkedBlockingDeque<PublishTask>

            private val publisherThreadStarted = AtomicBoolean(false)
            val initialized = AtomicBoolean(false)
            private val stopping = AtomicBoolean(false)

            // counters (for metrics/logs)
            @Volatile var droppedOldestCount: Long = 0
            @Volatile var publishFailures: Long = 0
            @Volatile var warnSampleRate: Long = 100

            // flush logging/metrics (count on ACKs)
            @Volatile var flushing: Boolean = false
            @Volatile var lastFlushPlanned: Int = 0
            @Volatile var lastFlushedCount: Int = 0

            // async confirms: seqNo -> inflight entry (task + timestamp)
            private data class InflightEntry(val task: PublishTask, val sentAtMs: Long)
            private val inFlight = ConcurrentSkipListMap<Long, InflightEntry>()
            @Volatile private var inFlightCap: Int = 1_000
            @Volatile private var confirmTimeoutMs: Long = 15_000L

            fun initOnce() {
                if (initialized.get()) return
                synchronized(this) {
                    if (initialized.get()) return

                    val amqp = AmqpConfig.fromEnv()
                    config = amqp
                    exchange = amqp.exchange

                    queue = LinkedBlockingDeque(amqp.bufferCapacity)
                    warnSampleRate = kotlin.math.max(1, amqp.bufferCapacity / 10).toLong()
                    inFlightCap = amqp.inFlightCapacity.takeIf { it > 0 } ?: 1_000
                    confirmTimeoutMs = amqp.confirmTimeoutMs.takeIf { it > 0 } ?: 15_000L

                    factory = ConnectionFactory().apply {
                        username = amqp.username
                        password = amqp.password
                        virtualHost = amqp.vHost
                        // Keepalive & timeouts
                        requestedHeartbeat = amqp.heartbeatSeconds
                        connectionTimeout = 10_000
                        handshakeTimeout = 10_000
                        shutdownTimeout = 5_000
                        // We manage reconnection & topology ourselves
                        isAutomaticRecoveryEnabled = false
                        isTopologyRecoveryEnabled = false
                        if (amqp.ssl) useSslProtocol()
                    }

                    startPublisherThread()
                    initialized.set(true)

                    logger.info(
                        "AMQP init: hosts={} vhost={} heartbeat={}s bufferCapacity={} maxInflight={} confirmTimeoutMs={}",
                        amqp.addresses.joinToString(",") { "${it.host}:${it.port}" },
                        amqp.vHost,
                        amqp.heartbeatSeconds,
                        amqp.bufferCapacity,
                        inFlightCap,
                        confirmTimeoutMs
                    )
                }
            }

            fun shutdown() {
                if (!publisherThreadStarted.get()) return
                stopping.set(true)
                runCatching { channel?.close() }
                runCatching { connection?.close() }
            }

            /** Enqueue with LRU semantics: try insert; if full, drop oldest then insert. */
            fun enqueue(task: PublishTask): Boolean {
                // Fast path
                if (queue.offerLast(task)) {
                    if (logger.isDebugEnabled) {
                        logger.debug("AMQP buffer ingress: size={} droppedTotal={}", queue.size, droppedOldestCount)
                    }
                    return true
                }

                // LRU drop path
                val dropped = queue.pollFirst()
                if (dropped != null) {
                    droppedOldestCount++
                    if (droppedOldestCount % warnSampleRate == 0L || droppedOldestCount == 1L) {
                        logger.warn(
                            "AMQP buffer full: dropped oldest (LRU). droppedTotal={} sampleRate={} (every {} drops)",
                            droppedOldestCount, warnSampleRate, warnSampleRate
                        )
                    }
                }

                val accepted = queue.offerLast(task)
                if (accepted && logger.isDebugEnabled) {
                    logger.debug("AMQP buffer ingress after drop: size={} droppedTotal={}", queue.size, droppedOldestCount)
                }
                return accepted
            }

            private fun startPublisherThread() {
                if (!publisherThreadStarted.compareAndSet(false, true)) return
                val tf = ThreadFactory { r -> Thread(r, "amqp-publisher").apply { isDaemon = true } }
                tf.newThread { publisherLoop() }.start()
                val cap = if (this::queue.isInitialized) queue.remainingCapacity() + queue.size else -1
                logger.info("AMQP publisher thread started (buffer capacity={})", cap)
            }

            /**
             * Main loop: keep connection/channel healthy, drain queue, handle reconnects.
             * Uses async confirms with a bounded in-flight window and a confirm-timeout watchdog.
             */
            private fun publisherLoop() {
                var backoff = BACKOFF_INITIAL
                var lastWatchdogCheck = 0L

                while (!stopping.get()) {
                    try {
                        ensureConnected()
                        backoff = BACKOFF_INITIAL

                        // Throttle on in-flight window (micro-park to avoid hot spin)
                        while (inFlight.size >= inFlightCap && !stopping.get()) {
                            LockSupport.parkNanos(THROTTLE_PARK_NS)
                        }

                        // Watchdog: every ~50ms, check oldest unconfirmed age
                        val now = System.currentTimeMillis()
                        if (now - lastWatchdogCheck >= 50) {
                            lastWatchdogCheck = now
                            val oldest = inFlight.firstEntry()
                            if (oldest != null) {
                                val age = now - oldest.value.sentAtMs
                                if (age >= confirmTimeoutMs) {
                                    logger.warn(
                                        "Publisher confirms stuck (oldest seq={} age={}ms >= {}ms). Recycling channel…",
                                        oldest.key, age, confirmTimeoutMs
                                    )
                                    repairChannelOrConnection() // will requeue all inFlight
                                    LockSupport.parkNanos(300_000) // ~0.3ms
                                    continue
                                }
                            }
                        }

                        // Block waiting for a task; small timeout so we can react to stop/repair
                        val task = queue.pollFirst(1, TimeUnit.SECONDS) ?: continue

                        var seqNo: Long? = null
                        try {
                            seqNo = channel!!.nextPublishSeqNo
                            inFlight[seqNo!!] = InflightEntry(task, System.currentTimeMillis())
                            channel!!.basicPublish(task.exchange, task.routingKey, task.props, task.body)
                            // ACK/NACK handled by listener

                        } catch (ex: Exception) {
                            publishFailures++
                            logger.warn("Publish attempt failed ({} total). Will repair and retry once: {}", publishFailures, ex.message, ex)
                            seqNo?.let { inFlight.remove(it) } // explicit key removal
                            queue.offerFirst(task) // preserve order
                            repairChannelOrConnection()
                            LockSupport.parkNanos(ON_ERROR_PARK_NS)
                        }
                    } catch (t: Throwable) {
                        logger.warn("AMQP publisher loop fault: {}. Backing off {} ms", t.message, backoff, t)
                        sleepQuiet(backoff)
                        backoff = min(backoff * 2, BACKOFF_MAX)
                    }
                }
                logger.info("AMQP publisher thread stopping. queueSize={} inFlight={}", queue.size, inFlight.size)
            }

            private fun ensureConnected() {
                val f = factory ?: error("AMQP ConnectionFactory not initialized")
                val amqp = config ?: error("AMQP config not initialized")
                if (connection?.isOpen == true && channel?.isOpen == true) return

                runCatching { channel?.close() }
                runCatching { connection?.close() }
                requeueAllInFlight()

                connection = f.newConnection(amqp.addresses.asList(), "keycloak-webhook")
                wireConnectionListeners(connection!!, f)
                channel = connection!!.createChannel()

                channel!!.confirmSelect()
                installConfirmListener(channel!!)

                ensureExchange(exchange!!)

                logger.info(
                    "AMQP connected: hosts={} vhost={} heartbeat={}s autoRecovery={} topologyRecovery={}",
                    amqp.addresses.joinToString(",") { "${it.host}:${it.port}" },
                    f.virtualHost,
                    f.requestedHeartbeat,
                    f.isAutomaticRecoveryEnabled,
                    f.isTopologyRecoveryEnabled
                )

                val pending = queue.size
                if (pending > 0) {
                    flushing = true
                    lastFlushPlanned = pending
                    lastFlushedCount = 0
                    logger.info("AMQP reconnected; starting flush of {} buffered messages", pending)
                }
            }

            private fun installConfirmListener(ch: Channel) {
                ch.addConfirmListener(
                    // ACK
                    { deliveryTag, multiple ->
                        if (multiple) {
                            val head = inFlight.headMap(deliveryTag, true)
                            val acked = head.size
                            head.clear()
                            if (flushing) {
                                lastFlushedCount += acked
                                maybeFinishFlush()
                            }
                        } else {
                            if (inFlight.remove(deliveryTag) != null && flushing) {
                                lastFlushedCount += 1
                                maybeFinishFlush()
                            }
                        }
                    },
                    // NACK
                    { deliveryTag, multiple ->
                        if (multiple) {
                            val head = inFlight.headMap(deliveryTag, true)
                            val entries = head.values.toList().asReversed()
                            head.clear()
                            var requeued = 0
                            for (entry in entries) {
                                queue.offerFirst(entry.task)
                                requeued++
                            }
                            logger.warn("Broker NACKed (multiple up to {}): requeued {} messages", deliveryTag, requeued)
                        } else {
                            inFlight.remove(deliveryTag)?.let {
                                queue.offerFirst(it.task)
                                logger.warn("Broker NACKed deliveryTag {}: requeued 1 message", deliveryTag)
                            }
                        }
                    }
                )
            }

            private fun maybeFinishFlush() {
                if (!flushing) return
                val remaining = queue.size
                if (remaining == 0 || lastFlushedCount >= lastFlushPlanned) {
                    logger.info("AMQP flush complete: published+ACKed {} messages", lastFlushedCount)
                    flushing = false
                    lastFlushPlanned = 0
                    lastFlushedCount = 0
                    resetMetricsAfterFlush()
                }
            }

            private fun requeueAllInFlight() {
                if (inFlight.isEmpty()) return
                val snapshot = inFlight.descendingMap().values.toList()
                inFlight.clear()
                for (entry in snapshot) queue.offerFirst(entry.task)
                logger.warn("Requeued {} unconfirmed messages after channel/connection drop", snapshot.size)
            }

            private fun repairChannelOrConnection() {
                val conn = connection
                if (conn != null && conn.isOpen) {
                    runCatching { channel?.close() }
                    requeueAllInFlight()
                    channel = conn.createChannel().also {
                        it.confirmSelect()
                        installConfirmListener(it)
                        // ensure exchange again on rebuilt channel
                        ensureExchange(exchange!!)
                    }
                    return
                }
                runCatching { channel?.close() }
                runCatching { connection?.close() }
                requeueAllInFlight()
                ensureConnected()
            }

            private fun ensureExchange(name: String) {
                val ch = channel ?: error("Channel not open")

                val passive = runCatching { ch.exchangeDeclarePassive(name) }
                if (passive.isSuccess) {
                    logger.info("Verified exchange '{}' exists", name)
                } else {
                    logger.warn("Exchange '{}' not found (passive): {}. Attempting declare.", name, passive.exceptionOrNull()?.message)
                    val declared = runCatching { ch.exchangeDeclare(name, "topic", true, false, null) }
                    if (declared.isSuccess) {
                        logger.info("Declared exchange '{}' (durable topic).", name)
                    } else {
                        logger.error(
                            "Failed to declare exchange '{}'. Publishing may fail. Reason: {}",
                            name, declared.exceptionOrNull()?.message, declared.exceptionOrNull()
                        )
                    }
                }
            }

            private fun wireConnectionListeners(connection: Connection, factory: ConnectionFactory) {
                connection.addShutdownListener(object : ShutdownListener {
                    override fun shutdownCompleted(cause: ShutdownSignalException?) {
                        if (cause == null) return
                        val msg = cause.message ?: "(no message)"
                        if (cause.isInitiatedByApplication) {
                            logger.info("AMQP connection shutdown (by application): {}", msg)
                        } else {
                            logger.warn("AMQP connection shutdown (by broker/IO): {}", msg, cause)
                        }
                    }
                })
                connection.addBlockedListener(object : BlockedListener {
                    override fun handleBlocked(reason: String?) =
                        logger.warn("AMQP connection BLOCKED: {}", reason ?: "(no reason)")
                    override fun handleUnblocked() =
                        logger.info("AMQP connection UNBLOCKED")
                })
            }

            private fun sleepQuiet(ms: Long) {
                try { Thread.sleep(ms) } catch (_: InterruptedException) {}
            }

            private fun resetMetricsAfterFlush() {
                droppedOldestCount = 0
                publishFailures = 0
                logger.info("AMQP metrics reset after successful flush: droppedOldestCount=0, publishFailures=0")
            }
        }

        private data class PublishTask(
            val exchange: String,
            val routingKey: String,
            val props: BasicProperties,
            val body: ByteArray
        )
    }
}
