package com.vymalo.keycloak.webhook.models

import com.rabbitmq.client.Address
import com.vymalo.keycloak.webhook.helper.*

data class AmqpConfig(
    val username: String,
    val password: String,
    val vHost: String,
    val ssl: Boolean,
    val exchange: String,
    val addresses: Array<Address>,
    val heartbeatSeconds: Int,
    val networkRecoveryMs: Long,
    val bufferCapacity: Int
) {
    companion object {
        private const val DEFAULT_PORT = 5672
        private const val DEFAULT_VHOST = "/"
        private const val DEFAULT_HEARTBEAT = 30
        private const val DEFAULT_NETWORK_RECOVERY_MS = 5_000L
        private const val DEFAULT_BUFFER_CAPACITY = 1_000

        fun fromEnv(): AmqpConfig {
            val username = amqpUsernameKey.cff()
            val password = amqpPasswordKey.cff()
            val vHost = amqpVHostKey.cfe { DEFAULT_VHOST }
            val ssl = amqpSslKey.bf()
            val exchange = amqpExchangeKey.cff()

            val heartbeatSeconds = amqpHeartbeatSecondsKey
                .cfe { DEFAULT_HEARTBEAT.toString() }
                .toIntOrNull() ?: DEFAULT_HEARTBEAT

            val networkRecoveryMs = amqpNetworkRecoveryMsKey
                .cfe { DEFAULT_NETWORK_RECOVERY_MS.toString() }
                .toLongOrNull() ?: DEFAULT_NETWORK_RECOVERY_MS

            val bufferCapacity = amqpWhHandlerBufferCapacityKey
                .cfe { DEFAULT_BUFFER_CAPACITY.toString() }
                .toIntOrNull() ?: DEFAULT_BUFFER_CAPACITY

            val addresses: Array<Address> = when (val addrs = amqpAdressesKey.cf()) {
                null, "", " " -> {
                    val host = amqpHostKey.cff()
                    val port = amqpPortKey.cfe { DEFAULT_PORT.toString() }.toIntOrNull() ?: DEFAULT_PORT
                    arrayOf(Address(host, port))
                }
                else -> Address.parseAddresses(addrs) // already Address[]
            }

            return AmqpConfig(
                username = username,
                password = password,
                vHost = vHost,
                ssl = ssl,
                exchange = exchange,
                addresses = addresses,
                heartbeatSeconds = heartbeatSeconds,
                networkRecoveryMs = networkRecoveryMs,
                bufferCapacity = bufferCapacity
            )
        }
    }
}
