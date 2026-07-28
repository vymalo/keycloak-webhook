package com.vymalo.keycloak.webhook.amqp

import com.vymalo.keycloak.webhook.core.AbstractWebhookEventListenerFactory

open class AmqpWebhookFactory : AbstractWebhookEventListenerFactory(AmqpWebhookHandler())
