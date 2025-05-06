package com.vymalo.keycloak.webhook

open class SyslogWebhookFactory : AbstractWebhookEventListenerFactory(SyslogWebhookHandler())