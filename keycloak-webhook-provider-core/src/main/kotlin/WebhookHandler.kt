package com.vymalo.keycloak.webhook.core

import org.keycloak.models.KeycloakSession

interface WebhookHandler {
    fun sendWebhook(request: WebhookPayload)
    fun close() {}
    fun getId(): String
    fun initHandler(session: KeycloakSession, clientId: String?) {}
}
