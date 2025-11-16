package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

data class HttpConfig(
    val username: String?,
    val password: String?,
    val baseUrls: List<String>,
) {
    companion object {
        fun fromEnv(): HttpConfig = HttpConfig(
            username = httpAuthUsernameKey.cf(),
            password = httpAuthPasswordKey.cf(),
            baseUrls = httpBaseBathKey.cff().split(',')
        )
    }
}

