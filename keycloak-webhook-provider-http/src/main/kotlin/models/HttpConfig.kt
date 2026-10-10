package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

/** Everything the HTTP transport needs, parsed and validated up front. */
data class HttpConfig(
    val username: String?,
    val password: String?,
    /** Every URL gets every event, in the order given. */
    val baseUrls: List<String>,
) {
    /** Keeps the password out of logs and exception messages. */
    override fun toString() = "HttpConfig(user=$username, baseUrls=$baseUrls)"

    companion object {
        fun from(source: ConfigSource): HttpConfig = source.read {
            HttpConfig(
                username = optional(httpAuthUsernameKey),
                password = optional(httpAuthPasswordKey),
                baseUrls = required(httpBaseBathKey).split(','),
            )
        }
    }
}
