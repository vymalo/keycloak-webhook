package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.models.HttpConfig

/** The `webhook-http` event listener, registered through META-INF/services. */
open class HttpWebhookFactory : WebhookEventListenerFactory(PROVIDER_ID, { HttpTransport(HttpConfig.from(it)) }) {
    companion object {
        const val PROVIDER_ID = "webhook-http"
    }
}
