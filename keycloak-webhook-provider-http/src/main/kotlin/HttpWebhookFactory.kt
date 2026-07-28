package com.vymalo.keycloak.webhook.http

import com.vymalo.keycloak.webhook.core.AbstractWebhookEventListenerFactory

open class HttpWebhookFactory : AbstractWebhookEventListenerFactory(::HttpWebhookHandler) {
    override fun getId(): String = HttpWebhookHandler.PROVIDER_ID
}
