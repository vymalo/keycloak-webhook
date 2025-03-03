package com.vymalo.keycloak.webhook

interface WebhookHandler {
    fun sendWebhook(request: WebhookPayload)
    fun close() {}
    fun getId(): String
    fun initHandler() {}
}