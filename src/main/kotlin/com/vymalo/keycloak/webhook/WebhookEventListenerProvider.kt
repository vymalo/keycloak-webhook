package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import com.vymalo.keycloak.webhook.service.WebhookHandler
import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.admin.AdminEvent
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class WebhookEventListenerProvider(
    private val handlers: Set<WebhookHandler>?,
    private val takeList: Set<String>?
) : EventListenerProvider {
    companion object {
        @JvmStatic
        private val LOG = LoggerFactory.getLogger(WebhookEventListenerProvider::class.java)
    }

    override fun close() {
    }

    override fun onEvent(event: Event) = send(
        event.id,
        event.time,
        event.realmId,
        event.clientId,
        event.userId,
        event.ipAddress,
        event.type.toString(),
        event.error,
        event.details,
        null,
        null
    )

    override fun onEvent(event: AdminEvent, includeRepresentation: Boolean) = send(
        event.id,
        event.time,
        event.realmId,
        event.authDetails?.clientId,
        event.authDetails?.userId,
        event.authDetails?.ipAddress,
        "${event.resourceType}-${event.operationType}",
        event.error,
        null,
        event.resourcePath,
        event.representation
    )

    private fun send(
        id: String,
        time: Long?,
        realmId: String,
        clientId: String?,
        userId: String?,
        ipAddress: String?,
        type: String,
        error: String?,
        details: Map<String, Any>?,
        resourcePath: String?,
        representation: String?,
    ) {
        if (takeList != null && type !in takeList) {
            LOG.debug("Event {} not in the taken list. Will be skipped ({}).", type, takeList)
            return
        }

        val request = WebhookRequest(
            id = id,
            time = if (time == null) null else BigDecimal(time),
            clientId = clientId,
            userId = userId,
            realmId = realmId,
            ipAddress = ipAddress,
            type = type,
            details = details,
            error = error,
            resourcePath = resourcePath,
            representation = representation
        )

        handlers?.forEach {
            try {
                LOG.debug("Sending [{}] webhook for event type {}: {}", it.handler(), type, request)
                it.sendWebhook(request)
            } catch (e: Throwable) {
                LOG.error("Could not send webhook", e)
            }
        }
    }
}