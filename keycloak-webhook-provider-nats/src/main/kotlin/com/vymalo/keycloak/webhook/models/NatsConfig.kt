package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

data class NatsConfig(
    val serverUrl: String,
    val subject: String,
    val username: String?,
    val password: String?,
    val token: String?,
    val credentials: String?,
    val ssl: Boolean,
) {
    companion object {
        fun fromEnv(): NatsConfig = NatsConfig(
            serverUrl = natsServerUrlKey.cff(),
            subject = natsSubjectKey.cff(),
            username = natsUsernameKey.cf(),
            password = natsPasswordKey.cf(),
            token = natsTokenKey.cf(),
            credentials = natsCredentialsKey.cf(),
            ssl = natsSsl.bf()
        )
    }
}