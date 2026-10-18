package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.infrastructure.ApiClient
import com.vymalo.keycloak.webhook.models.HttpConfig
import com.vymalo.keycloak.webhook.utils.toWebhookRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HttpWebhookHandler : WebhookHandler {
    private lateinit var webhookApis: List<WebhookApi>
    private lateinit var executor: ThreadPoolExecutor

    companion object {
        private val logger = LoggerFactory.getLogger(HttpWebhookHandler::class.java)
        const val PROVIDER_ID = "webhook-http"

        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    fun sendRequest(webhookApi: WebhookApi, request: WebhookPayload) {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                webhookApi.sendWebhook(request.toWebhookRequest())
                logger.debug("Webhook sent successfully on attempt ${attempt + 1}")
                return
            } catch (ex: Exception) {
                attempt++
                if (attempt >= MAX_RETRIES) {
                    logger.error("Failed to send webhook after $attempt attempts", ex)
                } else {
                    logger.warn("Attempt $attempt to send webhook failed: ${ex.message}", ex)
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        }
    }

    override fun sendWebhook(request: WebhookPayload) {
        // Snapshot the event details before leaving the Keycloak event thread.
        val payload = request.copy(details = request.details?.toMap())
        this.webhookApis.forEach { webhookApi ->
            this.executor.execute {
                try {
                    this.sendRequest(webhookApi, payload)
                } catch (e: Exception) {
                    logger.error("Unhandled error while sending webhook to ${webhookApi.baseUrl}", e)
                }
            }
        }
    }

    override fun getId(): String = PROVIDER_ID

    override fun initHandler() = initHandler(HttpConfig.fromEnv())

    @Synchronized
    internal fun initHandler(http: HttpConfig) {
        // Keycloak calls initHandler() for each session; the handler is shared.
        if (this::executor.isInitialized) return

        ApiClient.username = http.username
        ApiClient.password = http.password
        webhookApis = http.baseUrls.map { url -> WebhookApi(basePath = url) }

        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "keycloak-webhook-http").apply { isDaemon = true }
        }
        // Non-blocking backpressure: when the queue is full, drop the delivery and log,
        // rather than blocking the Keycloak thread or growing the heap forever.
        val dropPolicy = RejectedExecutionHandler { _, _ ->
            logger.warn("HTTP webhook queue is full, dropping event delivery")
        }

        executor = ThreadPoolExecutor(
            http.threads,
            http.threads,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(http.queueCapacity),
            threadFactory,
            dropPolicy
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
