package com.vymalo.keycloak.webhook

/**
 * Delivers payloads to one kind of destination (a broker, HTTP endpoints, a syslog server).
 *
 * A transport lives as long as the Keycloak server does: keep connections open between
 * events and release them in [close]. [publish] is called from many Keycloak request
 * threads at once and must not throw for delivery problems it can log itself.
 */
interface Transport : AutoCloseable {
    fun publish(payload: WebhookPayload)

    override fun close() {}
}
