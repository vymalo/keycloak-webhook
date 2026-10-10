package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.models.AmqpConfig

/** The `webhook-amqp` event listener, registered through META-INF/services. */
open class AmqpWebhookFactory : WebhookEventListenerFactory(PROVIDER_ID, { AmqpTransport(AmqpConfig.from(it)) }) {
    companion object {
        const val PROVIDER_ID = "webhook-amqp"
    }
}
