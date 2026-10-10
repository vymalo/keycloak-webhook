package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.infrastructure.ApiClient
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.models.HttpConfig
import com.vymalo.keycloak.webhook.utils.toWebhookRequest
import org.slf4j.LoggerFactory

/**
 * POSTs each event to every configured URL through the OpenAPI-generated client.
 *
 * Sending happens on the Keycloak request thread, one URL after another, retrying each
 * up to three times a second apart; that is the existing behaviour, kept as the default.
 */
class HttpTransport(config: HttpConfig) : Transport {

    init {
        // The generated client keeps basic-auth credentials in global state, so they are
        // shared by every URL. Per-URL credentials are on the roadmap.
        ApiClient.username = config.username
        ApiClient.password = config.password
    }

    private val endpoints = config.baseUrls.map { WebhookApi(basePath = it) }

    override fun publish(payload: WebhookPayload) {
        val request = payload.toWebhookRequest()
        endpoints.forEach { send(it, request) }
    }

    private fun send(endpoint: WebhookApi, request: WebhookRequest) {
        for (attempt in 1..ATTEMPTS) {
            try {
                endpoint.sendWebhook(request)
                logger.debug("Webhook sent successfully on attempt {}", attempt)
                return
            } catch (e: Exception) {
                if (attempt == ATTEMPTS) {
                    logger.error("Failed to send webhook after {} attempts", attempt, e)
                } else {
                    logger.warn("Attempt {} to send webhook failed: {}", attempt, e.message, e)
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
    }

    private companion object {
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L
        private val logger = LoggerFactory.getLogger(HttpTransport::class.java)
    }
}
