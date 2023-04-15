package com.vymalo.keycloak.webhook.service

import com.vymalo.keycloak.openapi.client.model.WebhookRequest

interface WebhookHandler {
    fun sendWebhook(request: WebhookRequest)

    fun handler(): String

    fun close() {}
}