package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.ConfigSource
import com.vymalo.keycloak.webhook.models.AmqpConfig

/** The `webhook-amqp` event listener, registered through META-INF/services. */
open class AmqpWebhookFactory : WebhookEventListenerFactory(PROVIDER_ID, ::openTransport) {
    companion object {
        const val PROVIDER_ID = "webhook-amqp"

        /** Sync, on the request thread, unless WEBHOOK_AMQP_PUBLISH_MODE=async asks for the background publisher. */
        private fun openTransport(source: ConfigSource): Transport {
            val config = AmqpConfig.from(source)
            return when (config.publishMode) {
                AmqpConfig.PublishMode.SYNC -> AmqpTransport(config)
                AmqpConfig.PublishMode.ASYNC -> AsyncAmqpTransport(config)
            }
        }
    }
}
