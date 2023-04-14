package com.vymalo.keycloak.webhook.service

import org.keycloak.events.Event
import org.keycloak.events.admin.AdminEvent

interface WebhookService {
    /**
     * Send the simple event
     */
    fun send(event: Event)

    /**
     * Send the admin event
     */
    fun send(event: AdminEvent)
}