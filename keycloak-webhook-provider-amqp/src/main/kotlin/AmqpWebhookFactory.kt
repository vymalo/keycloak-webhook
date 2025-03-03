package com.vymalo.keycloak.webhook

open class AmqpWebhookFactory : AbstractWebhookEventListenerFactory(AmqpWebhookHandler())