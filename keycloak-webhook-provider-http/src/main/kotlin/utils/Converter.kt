package com.vymalo.keycloak.webhook.utils

import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.WebhookPayload

fun WebhookPayload.toWebhookRequest() = WebhookRequest(
    type, realmId, id, time, clientId, userId, ipAddress, error, details, resourcePath, representation,
    userAttributes, realmRoles, clientRoles
)