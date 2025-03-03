package com.vymalo.keycloak.webhook.service

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.model.ClientConfig
import org.keycloak.utils.MediaType
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

class AmqpWebhookHandler(
    private val amqp: ClientConfig.Companion.Amqp
) : WebhookHandler {
    private var channel: Channel
    private var connection: Connection
    private val exchange: String = amqp.exchange
    private val connectionFactory: ConnectionFactory = ConnectionFactory().apply {
        username = amqp.username
        password = amqp.password
        virtualHost = amqp.vHost
        host = amqp.host
        port = amqp.port.toInt()
        isAutomaticRecoveryEnabled = true
        if (amqp.ssl) {
            useSslProtocol()
        }
    }

    companion object {
        @JvmStatic
        private val gson = Gson()
        private val logger = LoggerFactory.getLogger(AmqpWebhookHandler::class.java)

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
        private fun genRoutingKey(request: WebhookRequest): String =
            "KC_CLIENT.${request.realmId}.${request.clientId ?: "xxx"}.${request.userId ?: "xxx"}.${request.type}"
    }

    init {
        connection = connectionFactory.newConnection()
        channel = connection.createChannel()
    }

    /**
     * Ensures that the connection and channel are open.
     * If either is closed, it will try to reinitialize them up to 3 times.
     */
    private fun ensureConnection() {
        var attempts = 0
        while (attempts < 3 && (!connection.isOpen || !channel.isOpen)) {
            attempts++
            logger.debug("Attempting to re-establish connection (attempt $attempts)...")
            try {
                logger.debug("Closing connection and channel...")
                runCatching {
                    if (channel.isOpen) channel.close()
                }

                logger.debug("Channel closed. Closing connection...")
                runCatching {
                    if (connection.isOpen) connection.close()
                }

                logger.debug("Connection closed. Reinitializing connection and channel...")
                connection = connectionFactory.newConnection()
                channel = connection.createChannel()
                logger.debug("Reconnection attempt $attempts successful: connection.isOpen=${connection.isOpen}, channel.isOpen=${channel.isOpen}")
            } catch (ex: Exception) {
                logger.warn("Attempt $attempts failed to reinitialize connection: ${ex.message}", ex)
                Thread.sleep(1000L) // Wait 1 second before trying again
            }
        }
        if (!connection.isOpen || !channel.isOpen) {
            logger.error("Unable to re-establish connection after $attempts attempts.")
        }
    }

    override fun sendWebhook(request: WebhookRequest) {
        if (!connection.isOpen || !channel.isOpen) {
            ensureConnection()
        }
        if (!connection.isOpen || !channel.isOpen) {
            logger.warn("AMQP channel or connection is still closed. Unable to send webhook: {}", request)
            return
        }
        try {
            val requestStr = gson.toJson(request)
            channel.basicPublish(
                exchange,
                genRoutingKey(request),
                getMessageProps(request.javaClass.name),
                requestStr.toByteArray(StandardCharsets.UTF_8)
            )

            logger.debug("Webhook message sent: {}", request)
        } catch (ex: Exception) {
            logger.error("Failed to send webhook message", ex)
        }
    }

    override fun handler(): String = "amqp"

    override fun close() {
        runCatching {
            if (channel.isOpen) {
                channel.close()
            }
        }.onFailure { logger.warn("Error closing channel", it) }

        runCatching {
            if (connection.isOpen) {
                connection.close()
            }
        }.onFailure { logger.warn("Error closing connection", it) }
    }
}