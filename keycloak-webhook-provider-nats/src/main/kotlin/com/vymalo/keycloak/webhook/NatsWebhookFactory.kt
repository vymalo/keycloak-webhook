package com.vymalo.keycloak.webhook

open class NatsWebhookFactory : AbstractWebhookEventListenerFactory(NatsWebhookHandler())