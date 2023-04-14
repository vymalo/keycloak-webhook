package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.service.WebhookService
import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.admin.AdminEvent

class WebhookEventListenerProvider(private val service: WebhookService) : EventListenerProvider {
    override fun close() {
    }

    override fun onEvent(event: Event) = service.send(event)

    override fun onEvent(event: AdminEvent, includeRepresentation: Boolean) = service.send(event)
}