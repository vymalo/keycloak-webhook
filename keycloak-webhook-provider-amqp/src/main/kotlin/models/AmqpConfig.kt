package com.vymalo.keycloak.webhook.models

import com.rabbitmq.client.Address
import com.vymalo.keycloak.webhook.helper.*

/**
 * Everything the AMQP transport needs, parsed and validated up front.
 * Every setting added after the original ones defaults to the original behaviour.
 */
data class AmqpConfig(
    val username: String,
    val password: String,
    /**
     * Brokers to connect to. The client tries them in random order on every connect, so
     * load spreads across a cluster and a dead node is skipped. From WEBHOOK_AMQP_ADDRESSES,
     * or else the single WEBHOOK_AMQP_HOST/PORT pair.
     */
    val addresses: List<Address>,
    /** Defaults to the root vhost `/`, as the README has always said. */
    val vHost: String,
    val ssl: Boolean,
    /** Verifies the broker's certificate and host name. Null keeps the original TLS, which trusts any certificate. */
    val truststore: Truststore?,
    val exchange: String,
    /** Wait for the broker to confirm each message before the request moves on (#59). */
    val publisherConfirm: Boolean,
    val confirmTimeoutMs: Long,
    /** Null keeps the client's default (60 seconds). */
    val heartbeatSeconds: Int?,
    /** Marks messages persistent (delivery mode 2), so a durable queue keeps them across broker restarts. */
    val persistent: Boolean,
    /** Gives every message a random UUID `message-id`, so consumers can drop redeliveries. */
    val messageId: Boolean,
    /** Declares the exchange as a durable topic exchange on connect, instead of requiring it to exist. */
    val declareExchange: Boolean,
) {
    data class Truststore(val path: String, val password: String?, val type: String) {
        /** Keeps the password out of logs and exception messages. */
        override fun toString() = "Truststore(path=$path, type=$type)"
    }

    /** Keeps the password out of logs and exception messages. */
    override fun toString() =
        "AmqpConfig(user=$username, addresses=$addresses, vHost=$vHost, ssl=$ssl, truststore=$truststore, " +
            "exchange=$exchange, publisherConfirm=$publisherConfirm, confirmTimeoutMs=$confirmTimeoutMs, " +
            "heartbeatSeconds=$heartbeatSeconds, persistent=$persistent, messageId=$messageId, " +
            "declareExchange=$declareExchange)"

    companion object {
        fun from(source: ConfigSource): AmqpConfig = source.read {
            val ssl = flag(amqpSsl)
            AmqpConfig(
                username = required(amqpUsernameKey),
                password = required(amqpPasswordKey),
                addresses = addresses(),
                vHost = orDefault(amqpVHostKey, "/"),
                ssl = ssl,
                truststore = truststore(ssl),
                exchange = required(amqpExchangeKey),
                publisherConfirm = flag(amqpEnablePublisherConfirm),
                confirmTimeoutMs = long(amqpPublisherConfirmTimeout, default = 5_000),
                heartbeatSeconds = if (optional(amqpHeartbeatSecondsKey).isNullOrEmpty()) null else int(amqpHeartbeatSecondsKey),
                persistent = flag(amqpPersistentKey),
                messageId = flag(amqpMessageIdKey),
                declareExchange = flag(amqpDeclareExchangeKey),
            )
        }

        /**
         * `host:port` entries, comma-separated. An entry without a port uses the client's
         * default: 5672, or 5671 with TLS. HOST/PORT are only required when this is unset.
         */
        private fun ConfigReader.addresses(): List<Address> {
            val list = optional(amqpAddressesKey).orEmpty()
            if (list.isBlank()) return listOf(Address(required(amqpHostKey), int(amqpPortKey)))

            return list.split(',').map { it.trim() }.mapNotNull { entry ->
                val address = runCatching { Address.parseAddress(entry) }.getOrNull()?.takeIf { it.host.isNotBlank() }
                check(address != null) { "$amqpAddressesKey has an invalid entry '$entry', expected host or host:port" }
                address
            }
        }

        /** A truststore only makes sense with TLS on; silently switching TLS on would surprise more than an error. */
        private fun ConfigReader.truststore(ssl: Boolean): Truststore? {
            val path = optional(amqpSslTruststoreKey).orEmpty().ifEmpty { return null }
            check(ssl) { "$amqpSslTruststoreKey is set, but $amqpSsl is not \"true\"" }
            return Truststore(
                path = path,
                password = optional(amqpSslTruststorePasswordKey),
                type = orDefault(amqpSslTruststoreTypeKey, "PKCS12"),
            )
        }
    }
}
