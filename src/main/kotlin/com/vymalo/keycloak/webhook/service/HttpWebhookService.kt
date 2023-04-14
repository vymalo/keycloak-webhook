package com.vymalo.keycloak.webhook.service

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.model.WebhookRequest
import org.keycloak.events.Event
import org.keycloak.events.admin.AdminEvent
import org.slf4j.LoggerFactory
import java.math.BigDecimal


class HttpWebhookService(
    private val webhookApi: WebhookApi,
    private val takeList: Set<String>?
) : WebhookService {

    companion object {
        private val LOG = LoggerFactory.getLogger(HttpWebhookService::class.java)
    }

    override fun send(event: Event) = send(
        event.id,
        event.time,
        event.realmId,
        event.clientId,
        event.userId,
        event.ipAddress,
        event.type.toString(),
        event.error,
        event.details
    )

    override fun send(event: AdminEvent) = send(
        event.id,
        event.time,
        event.realmId,
        event.authDetails?.clientId,
        event.authDetails?.userId,
        event.authDetails?.ipAddress,
        "${event.resourceType}-${event.operationType}",
        event.error,
        null,
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
        details: Map<String, Any>?
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
        )
        LOG.debug("Sending webhook for event type {}: {}", type, request)

        try {
            webhookApi.sendWebhook(request)
        } catch (e: Throwable) {
            LOG.error("Could not send webhook", e)
        }
    }
}