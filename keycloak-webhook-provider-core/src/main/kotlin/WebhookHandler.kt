package com.vymalo.keycloak.webhook.core

import org.keycloak.models.KeycloakSession

interface WebhookHandler {
    fun sendWebhook(session: KeycloakSession, request: WebhookPayload)
    fun close() {}
    fun getId(): String
}
