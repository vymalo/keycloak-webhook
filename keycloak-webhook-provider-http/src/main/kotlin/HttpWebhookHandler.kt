package com.vymalo.keycloak.webhook.http

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.webhook.core.helper.ClientAttributeConfig
import com.vymalo.keycloak.webhook.core.helper.InternalTokenService
import com.vymalo.keycloak.webhook.core.WebhookHandler
import com.vymalo.keycloak.webhook.core.WebhookPayload
import com.vymalo.keycloak.webhook.http.models.HttpConfig
import com.vymalo.keycloak.webhook.http.utils.toWebhookRequest
import com.vymalo.keycloak.openapi.client.infrastructure.RequestConfig
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import org.keycloak.models.ClientModel
import org.keycloak.models.KeycloakSession
import okhttp3.Credentials
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class HttpWebhookHandler : WebhookHandler {
    companion object {
        private val logger = LoggerFactory.getLogger(HttpWebhookHandler::class.java)
        private val webhookApiCache = ConcurrentHashMap<String, AuthenticatedWebhookApi>()
        const val PROVIDER_ID = "webhook-http"
    }

    private fun sendRequest(
        webhookApi: AuthenticatedWebhookApi,
        getAuthenticationHeader: () -> String?,
        request: WebhookPayload,
    ) {
        var attempt = 0
        while (attempt < 3) {
            try {
                webhookApi.sendWebhook(
                    request.toWebhookRequest(),
                    getAuthenticationHeader()
                )
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

    override fun sendWebhook(session: KeycloakSession, request: WebhookPayload) {
        val httpConfig = HttpConfig.from(session, request.clientId)
        val sourceClient = ClientAttributeConfig.from(session, request.clientId).client

        if (httpConfig.authAudience != null && sourceClient == null) {
            throw IllegalStateException("HTTP webhook JWT auth requires a client context")
        }

        httpConfig.baseUrls
            .map(::createWebhookApi)
            .forEach { webhookApi ->
                sendRequest(webhookApi, {
                    this@HttpWebhookHandler.getAuthorizationHeader(
                        session,
                        httpConfig,
                        sourceClient
                    )
                }, request)
            }
    }

    override fun getId(): String = PROVIDER_ID

    private fun getAuthorizationHeader(
        session: KeycloakSession,
        httpConfig: HttpConfig,
        sourceClient: ClientModel?
    ): String? {
        val authAudience = httpConfig.authAudience
        val username = httpConfig.username
        val password = httpConfig.password

        return when {
            authAudience != null -> InternalTokenService.createBearerToken(
                session = session,
                realm = session.context.realm,
                client = sourceClient!!,
                audience = authAudience,
                ttlSeconds = httpConfig.authTtlSeconds
            ).let { token -> "Bearer $token" }
            username != null && password != null -> Credentials.basic(username, password)
            else -> null
        }
    }

    private fun createWebhookApi(baseUrl: String): AuthenticatedWebhookApi {
        return webhookApiCache.computeIfAbsent(baseUrl) { url ->
            AuthenticatedWebhookApi(basePath = url)
        }
    }

    private class AuthenticatedWebhookApi(
        basePath: String,
    ) : WebhookApi(basePath = basePath) {

        fun sendWebhook(request: WebhookRequest, authorizationHeader: String?) {
            val requestConfig = sendWebhookRequestConfig(request)
            applyAuthorizationHeader(requestConfig, authorizationHeader)
            request<WebhookRequest, Unit>(requestConfig)
        }

        private fun applyAuthorizationHeader(
            requestConfig: RequestConfig<WebhookRequest>,
            authorizationHeader: String?
        ) {
            if (authorizationHeader == null) {
                requestConfig.headers.remove("Authorization")
                return
            }

            requestConfig.headers["Authorization"] = authorizationHeader
        }
    }
}
