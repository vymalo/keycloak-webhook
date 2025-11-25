package com.vymalo.keycloak.webhook

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.keycloak.utils.MediaType
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

class AmqpWebhookHandler : WebhookHandler {
    private lateinit var channel: Channel
    private lateinit var connection: Connection
    private lateinit var exchange: String
    private lateinit var connectionFactory: ConnectionFactory
    private var usePublisherConfirm: Boolean = false
    private var confirmTimeout: Long = 5000

    companion object {
        const val PROVIDER_ID = "webhook-amqp"
        
        @JvmStatic
        private val gson = Gson()

        @JvmStatic
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
        private fun genRoutingKey(request: WebhookPayload): String =
            "KC_CLIENT.${request.realmId}.${request.clientId ?: "xxx"}.${request.userId ?: "xxx"}.${request.type}"
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

    override fun sendWebhook(request: WebhookPayload) {
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

            if (usePublisherConfirm) {
                channel.waitForConfirms(confirmTimeout)
            }

            logger.debug("Webhook message sent: {}", request)
        } catch (timeoutException: TimeoutException) {
            logger.error(
                "Publisher confirm timeout after ${confirmTimeout}ms — message delivery could not be verified, request: $request",
                timeoutException
            )
        } catch (ex: Exception) {
            logger.error("Failed to send webhook message", ex)
        }
    }

    override fun getId(): String = PROVIDER_ID

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


    override fun initHandler() {
        val amqp = AmqpConfig.fromEnv()

        exchange = amqp.exchange
        usePublisherConfirm = amqp.usePublisherConfirm
        confirmTimeout = amqp.publisherConfirmTimeout?.toLong() ?: confirmTimeout

        if (this::connection.isInitialized && this::channel.isInitialized && connection.isOpen && channel.isOpen) {
            logger.debug("Connection is already open")
            return
        }


        connectionFactory = ConnectionFactory().apply {
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

        connection = connectionFactory.newConnection()
        channel = connection.createChannel()
        if (amqp.usePublisherConfirm) {
            channel.confirmSelect()
        }
    }
}