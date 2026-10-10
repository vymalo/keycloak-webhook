package com.vymalo.keycloak.webhook.models

import com.vymalo.keycloak.webhook.helper.*

/** Everything the AMQP transport needs, parsed and validated up front. */
data class AmqpConfig(
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    /** Defaults to the root vhost `/`, as the README has always said. */
    val vHost: String,
    val ssl: Boolean,
    val exchange: String,
    /** Wait for the broker to confirm each message before the request moves on (#59). */
    val publisherConfirm: Boolean,
    val confirmTimeoutMs: Long,
) {
    /** Keeps the password out of logs and exception messages. */
    override fun toString() =
        "AmqpConfig(user=$username, host=$host:$port, vHost=$vHost, ssl=$ssl, exchange=$exchange, " +
            "publisherConfirm=$publisherConfirm, confirmTimeoutMs=$confirmTimeoutMs)"

    companion object {
        fun from(source: ConfigSource): AmqpConfig = source.read {
            AmqpConfig(
                username = required(amqpUsernameKey),
                password = required(amqpPasswordKey),
                host = required(amqpHostKey),
                port = int(amqpPortKey),
                vHost = orDefault(amqpVHostKey, "/"),
                ssl = flag(amqpSsl),
                exchange = required(amqpExchangeKey),
                publisherConfirm = flag(amqpEnablePublisherConfirm),
                confirmTimeoutMs = long(amqpPublisherConfirmTimeout, default = 5_000),
            )
        }
    }
}
