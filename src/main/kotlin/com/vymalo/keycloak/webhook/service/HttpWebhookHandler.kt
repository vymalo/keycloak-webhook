package com.vymalo.keycloak.webhook.service

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.infrastructure.ApiClient
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.model.ClientConfig
import org.slf4j.LoggerFactory

class HttpWebhookHandler(
    http: ClientConfig.Companion.Http
) : WebhookHandler {
    private val webhookApi: WebhookApi

    companion object {
        private val logger = LoggerFactory.getLogger(HttpWebhookHandler::class.java)
    }

    init {
        ApiClient.username = http.username
        ApiClient.password = http.password
        webhookApi = WebhookApi(basePath = http.baseUrl)
    }

    override fun sendWebhook(request: WebhookRequest) {
        var attempt = 0
        while (attempt < 3) {
            try {
                webhookApi.sendWebhook(request)
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

    override fun handler(): String = "http"
}
