package com.vymalo.keycloak.webhook.http

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.webhook.core.helper.ClientAttributeConfig
import com.vymalo.keycloak.webhook.core.helper.InternalTokenService
import com.vymalo.keycloak.webhook.core.WebhookHandler
import com.vymalo.keycloak.webhook.core.WebhookPayload
import com.vymalo.keycloak.webhook.http.models.HttpConfig
import com.vymalo.keycloak.webhook.http.utils.toWebhookRequest
import okhttp3.OkHttpClient
import org.keycloak.models.KeycloakSession
import okhttp3.Credentials
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

    override fun initHandler(session: KeycloakSession, clientId: String?) {
        val http = HttpConfig.from(session, clientId)
        val config = ClientAttributeConfig.from(session, clientId)
        val authorizationHeader = when {
            http.authAudience != null -> {
                val sourceClient = config.client
                    ?: throw IllegalStateException("HTTP webhook JWT auth requires a client context")
                InternalTokenService.createBearerToken(
                    session = session,
                    realm = session.context.realm,
                    client = sourceClient,
                    audience = http.authAudience,
                    ttlSeconds = http.authTtlSeconds
                ).let { token -> "Bearer $token" }
            }
            http.username != null && http.password != null -> Credentials.basic(http.username, http.password)
            else -> null
        }

        webhookApis = http.baseUrls.map { url -> createWebhookApi(url, authorizationHeader) }
    }

    private fun createWebhookApi(baseUrl: String, authorizationHeader: String?): WebhookApi {
        if (authorizationHeader == null) {
            return WebhookApi(basePath = baseUrl)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val authenticatedRequest = chain.request().newBuilder()
                    .header("Authorization", authorizationHeader)
                    .build()
                chain.proceed(authenticatedRequest)
            }
            .build()

        return WebhookApi(basePath = baseUrl, client = client)
    }
}
