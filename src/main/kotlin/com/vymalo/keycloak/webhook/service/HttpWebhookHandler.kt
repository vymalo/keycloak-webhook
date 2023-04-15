package com.vymalo.keycloak.webhook.service

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.infrastructure.ApiClient
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.model.ClientConfig

class HttpWebhookHandler(
    http: ClientConfig.Companion.Http
) : WebhookHandler {
    private val webhookApi: WebhookApi

    init {
        ApiClient.username = http.username
        ApiClient.password = http.password

        webhookApi = WebhookApi(basePath = http.baseUrl)
    }

    override fun sendWebhook(request: WebhookRequest) {
        webhookApi.sendWebhook(request)
    }

    override fun handler(): String = "http"
}