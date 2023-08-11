package com.vymalo.keycloak.webhook.service

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.model.ClientConfig
import org.keycloak.utils.MediaType
import java.nio.charset.StandardCharsets

class AmqpWebhookHandler(
    amqp: ClientConfig.Companion.Amqp
) : WebhookHandler {
    private var channel: Channel
    private var connection: Connection
    private val exchange: String

    companion object {
        @JvmStatic
        private val gson = Gson()

        @JvmStatic
        private fun getMessageProps(className: String): BasicProperties {
            val headers: MutableMap<String, Any> = HashMap()
            headers["__TypeId__"] = className
            val propsBuilder = BasicProperties.Builder()
                .appId("Keycloak/Kotlin")
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .contentEncoding("UTF-8")
            return propsBuilder.build()
        }

        @JvmStatic
        private fun genRoutingKey(request: WebhookRequest): String =
            "KC_CLIENT.${request.realmId}.${request.clientId ?: "xxx"}.${request.userId ?: "xxx"}.${request.type}"
    }

    init {
        exchange = amqp.exchange

        val connectionFactory = ConnectionFactory()
        connectionFactory.username = amqp.username
        connectionFactory.password = amqp.password
        connectionFactory.virtualHost = amqp.vHost
        connectionFactory.host = amqp.host
        connectionFactory.port = amqp.port.toInt()
        connectionFactory.isAutomaticRecoveryEnabled = true

        if (amqp.ssl) {
            connectionFactory.useSslProtocol()
        }

        connection = connectionFactory.newConnection()
        channel = connection.createChannel()
    }

    override fun sendWebhook(request: WebhookRequest) {
        val requestStr = gson.toJson(request)
        channel.basicPublish(
            exchange,
            genRoutingKey(request),
            getMessageProps(request.javaClass.name),
            requestStr.toByteArray(StandardCharsets.UTF_8)
        )
    }

    override fun handler(): String = "amqp"

    override fun close() {
        channel.close()
        connection.close()
    }
}