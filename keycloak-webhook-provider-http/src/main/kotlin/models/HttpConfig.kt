package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

data class HttpConfig(
    val username: String?,
    val password: String?,
    val baseUrl: String,
) {
    companion object {
        fun fromEnv(): HttpConfig {
            return HttpConfig(
                username = httpAuthUsernameKey.cf(),
                password = httpAuthPasswordKey.cf(),
                baseUrl = httpBaseBathKey.cff()
            )
        }
    }
}

