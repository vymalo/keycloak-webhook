package com.vymalo.keycloak.webhook.amqp

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.webhook.amqp.models.AmqpConfig
import com.vymalo.keycloak.webhook.core.WebhookHandler
import com.vymalo.keycloak.webhook.core.WebhookPayload
import org.keycloak.models.KeycloakSession
import org.keycloak.utils.MediaType
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

class AmqpWebhookHandler : WebhookHandler {
    companion object {
        const val PROVIDER_ID = "webhook-amqp"
        private const val defaultConfirmTimeout = 5000L

        @JvmStatic
        private val gson = Gson()

        @JvmStatic
        private val logger = LoggerFactory.getLogger(AmqpWebhookHandler::class.java)

        @JvmStatic
        private val connectionCache = ConcurrentHashMap<AmqpConfig, CachedAmqpConnection>()

        @JvmStatic
        private fun getMessageProps(className: String): BasicProperties {
            val headers: MutableMap<String, Any> = HashMap()
            headers["__TypeId__"] = className
            return BasicProperties.Builder()
                .appId("Keycloak/Kotlin")
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .contentEncoding("UTF-8")
                .build()
        }

        @JvmStatic
        private fun genRoutingKey(request: WebhookPayload): String =
            "KC_CLIENT.${request.realmId}.${request.clientId ?: "xxx"}.${request.userId ?: "xxx"}.${request.type}"
    }

    override fun sendWebhook(session: KeycloakSession, request: WebhookPayload) {
        val connection = getOrCreateConnection(AmqpConfig.from(session, request.clientId))

        try {
            val requestStr = gson.toJson(request)
            connection.publish(
                genRoutingKey(request),
                getMessageProps(request.javaClass.name),
                requestStr.toByteArray(StandardCharsets.UTF_8)
            )

            logger.debug("Webhook message sent: {}", request)
        } catch (timeoutException: TimeoutException) {
            logger.error(
                "Publisher confirm timeout after ${connection.confirmTimeout}ms — message delivery could not be verified, request: $request",
                timeoutException
            )
        } catch (ex: Exception) {
            logger.error("Failed to send webhook message", ex)
        }
    }

    override fun getId(): String = PROVIDER_ID

    override fun close() {
    }

    private fun getOrCreateConnection(config: AmqpConfig): CachedAmqpConnection {
        return connectionCache.computeIfAbsent(config, ::CachedAmqpConnection)
    }

    private class CachedAmqpConnection(private val config: AmqpConfig) {
        private val connectionFactory = ConnectionFactory().apply {
            username = config.username
            password = config.password
            virtualHost = config.vHost
            host = config.host
            port = config.port.toInt()
            isAutomaticRecoveryEnabled = true
            if (config.ssl) {
                useSslProtocol()
            }
        }

        private var connection: Connection = connectionFactory.newConnection()
        private var channel: Channel = connection.createChannel().also {
            if (config.usePublisherConfirm) {
                it.confirmSelect()
            }
        }

        val confirmTimeout: Long = config.publisherConfirmTimeout?.toLong() ?: defaultConfirmTimeout

        @Synchronized
        fun publish(routingKey: String, properties: BasicProperties, body: ByteArray) {
            ensureConnection()

            if (!connection.isOpen || !channel.isOpen) {
                throw IllegalStateException("AMQP channel or connection is still closed")
            }

            channel.basicPublish(config.exchange, routingKey, properties, body)
            if (config.usePublisherConfirm) {
                channel.waitForConfirms(confirmTimeout)
            }
        }

        private fun ensureConnection() {
            var attempts = 0
            while (attempts < 3 && (!connection.isOpen || !channel.isOpen)) {
                attempts++
                logger.debug("Attempting to re-establish connection (attempt $attempts)...")
                try {
                    runCatching {
                        if (channel.isOpen) channel.close()
                    }
                    runCatching {
                        if (connection.isOpen) connection.close()
                    }

                    connection = connectionFactory.newConnection()
                    channel = connection.createChannel().also {
                        if (config.usePublisherConfirm) {
                            it.confirmSelect()
                        }
                    }
                    logger.debug("Reconnection attempt $attempts successful: connection.isOpen=${connection.isOpen}, channel.isOpen=${channel.isOpen}")
                } catch (ex: Exception) {
                    logger.warn("Attempt $attempts failed to reinitialize connection: ${ex.message}", ex)
                    Thread.sleep(1000L)
                }
            }

            if (!connection.isOpen || !channel.isOpen) {
                logger.error("Unable to re-establish connection after $attempts attempts.")
            }
        }
    }
}
