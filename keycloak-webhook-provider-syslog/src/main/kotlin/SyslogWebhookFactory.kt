package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.models.SyslogConfig

/** The `webhook-syslog` event listener, registered through META-INF/services. */
open class SyslogWebhookFactory : WebhookEventListenerFactory(PROVIDER_ID, { SyslogTransport(SyslogConfig.from(it)) }) {
    companion object {
        const val PROVIDER_ID = "webhook-syslog"
    }
}
