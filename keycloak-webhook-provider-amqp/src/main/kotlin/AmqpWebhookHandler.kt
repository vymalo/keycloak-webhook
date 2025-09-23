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
import java.time.Duration
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Self-healing AMQP webhook handler with a bounded LRU buffer.
 * - Request thread enqueues (non-blocking) into a capacity-limited deque.
 * - Background publisher thread drains the queue and handles reconnects.
 */
class AmqpWebhookHandler : WebhookHandler {

    override fun getId(): String = PROVIDER_ID

    override fun initHandler() {
        Shared.initOnce()
    }

    override fun close() {
        // Intentionally no-op: this handler can be short-lived; the Shared publisher lives for the process.
        // TODO
        // If you have a Factory with a real shutdown hook, call Shared.shutdown() there.
        // So far I couldn't isolate a good candiate for that. AmqpWebhookFactory is per request as well.
    }

    override fun sendWebhook(request: WebhookPayload) {
        if (!Shared.initialized.get()) Shared.initOnce()

        val body = gson.toJson(request).toByteArray(StandardCharsets.UTF_8)
        val routingKey = genRoutingKey(request)
        val props = getMessageProps(request.javaClass.name)

        val ok = Shared.enqueue(PublishTask(Shared.exchange ?: "", routingKey, props, body))
        if (!ok) {
            // This should not happen because enqueue drops LRU and then inserts, but keep a guard.
            logger.error("AMQP buffer rejected message even after LRU drop; message lost. rk={}", routingKey)
        }
    }

    companion object {
        const val PROVIDER_ID = "webhook-amqp"

        private const val DEFAULT_HEARTBEAT_SECONDS = 30
        private const val DEFAULT_NETWORK_RECOVERY_MS = 5_000

        // Buffer capacity: keep the last N messages; on overflow, drop oldest.
        private const val BUFFER_CAPACITY = 1_000

        // Backoff limits for reconnect
        private val BACKOFF_INITIAL = 250L
        private val BACKOFF_MAX = 2_000L

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

            private val queue = LinkedBlockingDeque<PublishTask>(BUFFER_CAPACITY)
            private val publisherThreadStarted = AtomicBoolean(false)
            val initialized = AtomicBoolean(false)
            private val stopping = AtomicBoolean(false)

            // counters (for metrics/logs)
            @Volatile var droppedOldestCount: Long = 0
            @Volatile var publishFailures: Long = 0

            // ---- added for flush logging/metrics ----
            @Volatile var flushing: Boolean = false
            @Volatile var lastFlushPlanned: Int = 0
            @Volatile var lastFlushedCount: Int = 0

            fun initOnce() {
                if (initialized.get()) return
                synchronized(this) {
                    if (initialized.get()) return

                    val amqp = AmqpConfig.fromEnv()
                    exchange = amqp.exchange

                    factory = ConnectionFactory().apply {
                        username = amqp.username
                        password = amqp.password
                        virtualHost = amqp.vHost
                        host = amqp.host
                        port = amqp.port.toInt()
                        // Keepalive & timeouts
                        requestedHeartbeat = DEFAULT_HEARTBEAT_SECONDS
                        connectionTimeout = 10_000
                        handshakeTimeout = 10_000
                        shutdownTimeout = 5_000
                        // Resiliency
                        isAutomaticRecoveryEnabled = true
                        isTopologyRecoveryEnabled = true
                        networkRecoveryInterval = DEFAULT_NETWORK_RECOVERY_MS.toLong()
                        if (amqp.ssl) useSslProtocol()
                    }

                    startPublisherThread()
                    initialized.set(true)
                }
            }

            fun shutdown() {
                if (!publisherThreadStarted.get()) return
                stopping.set(true)
                // best-effort close
                runCatching { channel?.close() }
                runCatching { connection?.close() }
            }

            /**
             * Enqueue with LRU semantics: drop oldest if full, then insert newest.
             */
            fun enqueue(task: PublishTask): Boolean {
                // Fast path
                if (queue.offerLast(task)) {
                    // --- ingress log ---
                    logger.info("AMQP buffer ingress: size={} droppedTotal={}", queue.size, droppedOldestCount)
                    return true
                }

                // LRU drop path
                val dropped = queue.pollFirst()
                if (dropped != null) {
                    droppedOldestCount++
                    logger.warn("AMQP buffer full: dropped oldest (LRU). droppedTotal={}", droppedOldestCount)
                }
                // Try once more
                val accepted = queue.offerLast(task)
                if (accepted) {
                    // --- ingress log after LRU drop ---
                    logger.info("AMQP buffer ingress: DROPPED_OLDEST size={} droppedTotal={}", queue.size, droppedOldestCount)
                }
                return accepted
            }

            private fun startPublisherThread() {
                if (!publisherThreadStarted.compareAndSet(false, true)) return
                val tf = ThreadFactory { r ->
                    Thread(r, "amqp-publisher").apply { isDaemon = true }
                }
                tf.newThread { publisherLoop() }.start()
                logger.info("AMQP publisher thread started (buffer capacity={})", BUFFER_CAPACITY)
            }

            /**
             * Main loop: keep connection/channel healthy, drain queue, on failure repair & retry.
             */
            private fun publisherLoop() {
                var backoff = BACKOFF_INITIAL
                var ensuredExchange = false

                while (!stopping.get()) {
                    try {
                        ensureConnected()
                        if (!ensuredExchange) {
                            ensureExchange(exchange!!)
                            ensuredExchange = true
                        }
                        backoff = BACKOFF_INITIAL // reset backoff after a healthy connect

                        // Block waiting for a task; small timeout so we can react to stop/repair
                        val task = queue.pollFirst(1, java.util.concurrent.TimeUnit.SECONDS)
                        if (task == null) continue

                        try {
                            channel!!.basicPublish(task.exchange, task.routingKey, task.props, task.body)

                            // --- egress counting when flushing after reconnect ---
                            if (flushing) {
                                lastFlushedCount++
                                val remaining = queue.size
                                if (remaining == 0 || lastFlushedCount >= lastFlushPlanned) {
                                    logger.info("AMQP flush complete: published {} messages", lastFlushedCount)
                                    flushing = false
                                    lastFlushPlanned = 0
                                    lastFlushedCount = 0
                                    resetMetricsAfterFlush()
                                }
                            }
                        } catch (ex: Exception) {
                            publishFailures++
                            logger.warn("Publish failed ({} total). Will repair and retry once: {}", publishFailures, ex.message, ex)
                            // Requeue to the front to preserve order for the failing message
                            queue.offerFirst(task)
                            repairChannelOrConnection()
                            // short sleep to avoid hot loop if broker just closed us
                            Thread.sleep(100)
                        }
                    } catch (t: Throwable) {
                        // Connection-level problem or unexpected error. Back off and retry.
                        logger.warn("AMQP publisher loop fault: {}. Backing off {} ms", t.message, backoff, t)
                        sleepQuiet(backoff)
                        backoff = min(backoff * 2, BACKOFF_MAX)
                    }
                }
                logger.info("AMQP publisher thread stopping. queueSize={}", queue.size)
            }

            private fun ensureConnected() {
                val f = factory ?: error("AMQP ConnectionFactory not initialized")
                if (connection?.isOpen == true && channel?.isOpen == true) return

                // Close any half-open remnants
                runCatching { if (channel?.isOpen == true) channel?.close() }
                runCatching { if (connection?.isOpen == true) connection?.close() }

                // Open fresh connection & channel
                connection = f.newConnection("keycloak-webhook")
                wireConnectionListeners(connection!!, f)
                channel = connection!!.createChannel()
                logger.info(
                    "AMQP connected: host={} vhost={} heartbeat={}s autoRecovery={} topologyRecovery={}",
                    f.host, f.virtualHost, f.requestedHeartbeat, f.isAutomaticRecoveryEnabled, f.isTopologyRecoveryEnabled
                )

                // --- egress: flush start log ---
                val pending = queue.size
                if (pending > 0) {
                    flushing = true
                    lastFlushPlanned = pending
                    lastFlushedCount = 0
                    logger.info("AMQP reconnected; starting flush of {} buffered messages", pending)
                }
            }

            private fun repairChannelOrConnection() {
                // Try cheap path first: just rebuild channel if conn is open
                val conn = connection
                if (conn != null && conn.isOpen) {
                    runCatching { channel?.close() }
                    channel = conn.createChannel()
                    return
                }
                // Otherwise do full reconnect
                runCatching { channel?.close() }
                runCatching { connection?.close() }
                ensureConnected()
            }

            private fun ensureExchange(name: String) {
                // Try passive check, then declare a durable topic exchange if missing
                val ch = channel ?: error("Channel not open")
                runCatching { ch.exchangeDeclarePassive(name) }
                    .onSuccess { logger.info("Verified exchange '{}' exists", name); return }
                    .onFailure { ex ->
                        logger.warn("Exchange '{}' not found (passive): {}. Attempting declare.", name, ex.message)
                    }
                runCatching { ch.exchangeDeclare(name, "topic", true, false, null) }
                    .onSuccess { logger.info("Declared exchange '{}' (durable topic).", name) }
                    .onFailure { ex2 ->
                        logger.error("Failed to declare exchange '{}'. Publishing may fail. Reason: {}", name, ex2.message, ex2)
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

            // ---- reset counters after a successful flush ----
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
