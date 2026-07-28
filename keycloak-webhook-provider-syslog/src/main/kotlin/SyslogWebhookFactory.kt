package com.vymalo.keycloak.webhook.syslog

import com.vymalo.keycloak.webhook.core.AbstractWebhookEventListenerFactory

open class SyslogWebhookFactory : AbstractWebhookEventListenerFactory(SyslogWebhookHandler())
