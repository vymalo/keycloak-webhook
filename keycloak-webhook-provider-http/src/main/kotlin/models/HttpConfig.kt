package com.vymalo.keycloak.webhook.http.models

import com.vymalo.keycloak.webhook.core.helper.*
import org.keycloak.models.KeycloakSession

data class HttpConfig(
    val authAudience: String?,
    val authTtlSeconds: Long,
    val username: String?,
    val password: String?,
    val baseUrls: List<String>,
) {
    companion object {
        private const val defaultTokenTtlSeconds = 60L

        fun from(session: KeycloakSession, clientId: String?): HttpConfig {
            val config = ClientAttributeConfig.from(session, clientId)
            val username = config.string(httpAuthUsernameKey)
            val password = config.string(httpAuthPasswordKey)

            if ((username == null) != (password == null)) {
                throw IllegalStateException("HTTP webhook basic auth requires both $httpAuthUsernameKey and $httpAuthPasswordKey")
            }

            return HttpConfig(
                authAudience = config.string(httpAuthAudienceKey),
                authTtlSeconds = config.long(httpAuthTtlSecondsKey) ?: defaultTokenTtlSeconds,
                username = username,
                password = password,
                baseUrls = config.requiredList(httpBaseBathKey)
            )
        }
    }
}
