package com.vymalo.keycloak.webhook.utils

import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.WebhookPayload

fun WebhookPayload.toWebhookRequest() = WebhookRequest(
    type = type,
    realmId = realmId,
    id = id,
    time = time,
    clientId = clientId,
    userId = userId,
    ipAddress = ipAddress,
    error = error,
    details = details,
    resourcePath = resourcePath,
    representation = representation,
    sessionId = sessionId
)