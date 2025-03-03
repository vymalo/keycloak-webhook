package com.vymalo.keycloak.webhook

open class HttpWebhookFactory : AbstractWebhookEventListenerFactory(HttpWebhookHandler())
