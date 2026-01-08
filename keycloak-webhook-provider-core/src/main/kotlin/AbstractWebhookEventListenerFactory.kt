package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.cf
import com.vymalo.keycloak.webhook.helper.eventsTakenKey
import org.keycloak.Config
import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.events.admin.AdminEvent
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ServerInfoAwareProviderFactory
import org.slf4j.LoggerFactory
import java.math.BigDecimal

abstract class AbstractWebhookEventListenerFactory(
    private val delegate: WebhookHandler
) : EventListenerProviderFactory,
    ServerInfoAwareProviderFactory,
    EventListenerProvider,
    WebhookHandler by delegate {
    private var takeList: Set<String>? = null

    override fun getOperationalInfo() = mapOf("version" to "0.10.0-rc.1")

    companion object {
        @JvmStatic
        private val LOG = LoggerFactory.getLogger(AbstractWebhookEventListenerFactory::class.java)
    }

    override fun create(session: KeycloakSession): EventListenerProvider {
        ensureParametersInit()
        return this
    }

    @Synchronized
    private fun ensureParametersInit() {
        synchronized(delegate) {
            delegate.initHandler()

            takeList = eventsTakenKey.cf()
                ?.trim()
                ?.split(",")
                ?.map { it.trim() }
                ?.toSet()
        }
    }

    override fun init(config: Config.Scope) {}

    override fun postInit(factory: KeycloakSessionFactory) {}

    override fun onEvent(event: Event) = send(
        event.id,
        event.time,
        event.realmId,
        event.getEventRealmName(),
        event.sessionId,
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
        event.getAdminEventRealmName(),
        null,
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
        realmName: String?,
        sessionId: String?,
        clientId: String?,
        userId: String?,
        ipAddress: String?,
        type: String,
        error: String?,
        details: Map<String, Any>?,
        resourcePath: String?,
        representation: String?,
    ) {
        if (takeList != null && type !in takeList!!) {
            LOG.debug("Event {} not in the taken list. Will be skipped ({}).", type, takeList)
            return
        }

        val request = WebhookPayload(
            id = id,
            time = if (time == null) null else BigDecimal(time),
            clientId = clientId,
            userId = userId,
            realmId = realmId,
            realmName = realmName,
            sessionId = sessionId,
            ipAddress = ipAddress,
            type = type,
            details = details,
            error = error,
            resourcePath = resourcePath,
            representation = representation
        )

        try {
            LOG.debug("Sending [{}] webhook for event type {}: {}", delegate.getId(), type, request)
            delegate.sendWebhook(request)
        } catch (e: Throwable) {
            LOG.error("Could not send webhook", e)
        }
    }
}