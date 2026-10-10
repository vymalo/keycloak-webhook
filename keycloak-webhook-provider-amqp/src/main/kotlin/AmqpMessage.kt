package com.vymalo.keycloak.webhook

import com.google.gson.Gson
import com.rabbitmq.client.AMQP.BasicProperties
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.keycloak.utils.MediaType
import java.util.UUID

/**
 * One event as it goes on the wire. Built once per event, so a message that has to be
 * sent again keeps its body and its message id, and consumers can recognise the repeat.
 */
internal class AmqpMessage(
    val routingKey: String,
    val properties: BasicProperties,
    val body: ByteArray,
    /** How often the broker refused this message so far; the async publisher gives up after a few. */
    val refusals: Int = 0,
) {
    fun refusedOnceMore() = AmqpMessage(routingKey, properties, body, refusals + 1)

    companion object {
        private val gson = Gson()

        /** [baseProperties] are the ones from [properties]; built once per transport and passed in here. */
        fun of(payload: WebhookPayload, config: AmqpConfig, baseProperties: BasicProperties) = AmqpMessage(
            routingKey = routingKey(payload),
            properties = if (config.messageId) baseProperties.builder().messageId(UUID.randomUUID().toString()).build()
            else baseProperties,
            body = gson.toJson(payload).toByteArray(Charsets.UTF_8),
        )

        /** `KC_CLIENT.<realm>.<client>.<user>.<type>` so consumers can bind on any part, e.g. `KC_CLIENT.*.*.*.LOGIN`. */
        fun routingKey(payload: WebhookPayload) =
            "KC_CLIENT.${payload.realmId}.${payload.clientId ?: "xxx"}.${payload.userId ?: "xxx"}.${payload.type}"

        /**
         * The same for every message except the optional message id. `__TypeId__` is the header
         * Spring AMQP reads to pick the target class, so Spring consumers can deserialize without extra mapping.
         */
        fun properties(config: AmqpConfig): BasicProperties = BasicProperties.Builder()
            .appId("Keycloak/Kotlin")
            .headers(mapOf<String, Any>("__TypeId__" to WebhookPayload::class.java.name))
            .contentType(MediaType.APPLICATION_JSON)
            .contentEncoding("UTF-8")
            .apply { if (config.persistent) deliveryMode(2) }
            .build()
    }
}
