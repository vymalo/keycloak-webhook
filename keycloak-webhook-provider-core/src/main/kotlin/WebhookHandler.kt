package com.vymalo.keycloak.webhook.core

interface WebhookHandler {
    fun sendWebhook(request: WebhookPayload)
    fun close() {}
    fun getId(): String
    fun initHandler() {}
}
