package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

data class AmqpConfig(
    val username: String,
    val password: String,
    val host: String,
    val port: String,
    val vHost: String?,
    val ssl: Boolean,
    val exchange: String,
    val deliveryMode: String?
) {
    companion object {
        fun fromEnv(): AmqpConfig = AmqpConfig(
            username = amqpUsernameKey.cff(),
            password = amqpPasswordKey.cff(),
            host = amqpHostKey.cff(),
            port = amqpPortKey.cff(),
            vHost = amqpVHostKey.cf(),
            ssl = amqpSsl.bf(),
            exchange = amqpExchangeKey.cff(),
            deliveryMode = amqpDeliveryMode.cf()
        )
    }
}