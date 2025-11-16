package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.infrastructure.ApiClient
import com.vymalo.keycloak.webhook.models.HttpConfig
import com.vymalo.keycloak.webhook.utils.toWebhookRequest
import org.slf4j.LoggerFactory

class HttpWebhookHandler : WebhookHandler {
    private lateinit var webhookApis: List<WebhookApi>

    companion object {
        private val logger = LoggerFactory.getLogger(HttpWebhookHandler::class.java)
        const val PROVIDER_ID = "webhook-http"
    }

    fun sendRequest(webhookApi: WebhookApi, request: WebhookPayload) {
        var attempt = 0
        while (attempt < 3) {
            try {
                webhookApi.sendWebhook(request.toWebhookRequest())
                logger.debug("Webhook sent successfully on attempt ${attempt + 1}")
                break // Exit loop if successful
            } catch (ex: Exception) {
                attempt++
                if (attempt >= 3) {
                    logger.error("Failed to send webhook after $attempt attempts", ex)
                } else {
                    logger.warn("Attempt $attempt to send webhook failed: ${ex.message}", ex)
                    Thread.sleep(1000L) // Wait before retrying
                }
            }
        }
    }

    override fun sendWebhook(request: WebhookPayload) {
        this.webhookApis.forEach { webhookApi -> this.sendRequest(webhookApi, request) }
    }

    override fun getId(): String = PROVIDER_ID

    override fun initHandler() {
        val http = HttpConfig.fromEnv()

        ApiClient.username = http.username
        ApiClient.password = http.password
        webhookApis = http.baseUrls.map { url ->  WebhookApi(basePath = url)}
    }
}
