package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

data class HttpConfig(
    val username: String?,
    val password: String?,
    val baseUrls: List<String>,
    val threads: Int,
    val queueCapacity: Int,
) {
    companion object {
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_QUEUE_CAPACITY = 1000

        fun fromEnv(): HttpConfig = fromConfig { it.cf() }

        internal fun fromConfig(get: (String) -> String?): HttpConfig = HttpConfig(
            username = get(httpAuthUsernameKey),
            password = get(httpAuthPasswordKey),
            baseUrls = get(httpBaseBathKey)!!.split(','),
            threads = (get(httpThreadsKey)?.toIntOrNull() ?: DEFAULT_THREADS).coerceAtLeast(1),
            queueCapacity = (get(httpQueueCapacityKey)?.toIntOrNull() ?: DEFAULT_QUEUE_CAPACITY).coerceAtLeast(1)
        )
    }
}
