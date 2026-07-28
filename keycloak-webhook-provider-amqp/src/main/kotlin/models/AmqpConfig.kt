package com.vymalo.keycloak.webhook.amqp.models

import com.vymalo.keycloak.webhook.core.helper.*
import org.keycloak.models.KeycloakSession

data class AmqpConfig(
    val username: String,
    val password: String,
    val host: String,
    val port: String,
    val vHost: String?,
    val ssl: Boolean,
    val exchange: String,
    val usePublisherConfirm: Boolean,
    val publisherConfirmTimeout: String?
) {
    companion object {
        fun from(session: KeycloakSession, clientId: String?): AmqpConfig {
            val config = ClientAttributeConfig.from(session, clientId)
            return AmqpConfig(
                username = config.requiredString(amqpUsernameKey),
                password = config.requiredString(amqpPasswordKey),
                host = config.requiredString(amqpHostKey),
                port = config.requiredString(amqpPortKey),
                vHost = config.string(amqpVHostKey),
                ssl = config.boolean(amqpSsl),
                exchange = config.requiredString(amqpExchangeKey),
                usePublisherConfirm = config.boolean(amqpEnablePublisherConfirm),
                publisherConfirmTimeout = config.string(amqpPublisherConfirmTimeout)
            )
        }
    }
}
