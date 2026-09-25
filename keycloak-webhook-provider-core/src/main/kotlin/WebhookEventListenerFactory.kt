package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.ConfigSource
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

/**
 * Keycloak's entry point for one transport (AMQP, HTTP or Syslog).
 *
 * Keycloak keeps one factory for the server's whole life, calls [create] for every
 * session, and closes that session's provider when the session ends. So the costly
 * part, the [Transport] and its connection, belongs here and is closed only in
 * [close] at shutdown; providers are throwaway views onto it.
 *
 * The transport is opened on the first [create], not in [postInit]: Keycloak runs
 * postInit for every installed provider jar, but only creates listeners that are
 * enabled in a realm, and people often install all jars while configuring one.
 */
abstract class WebhookEventListenerFactory(
    private val providerId: String,
    private val openTransport: (ConfigSource) -> Transport,
    private val config: ConfigSource = ConfigSource.Environment,
) : EventListenerProviderFactory, ServerInfoAwareProviderFactory {

    /** What every session shares. Built once, together, so a bad config never leaves half of it behind. */
    private class Wiring(val transport: Transport, val filter: EventFilter)

    @Volatile
    private var wiring: Wiring? = null

    override fun getId(): String = providerId

    override fun getOperationalInfo() = mapOf("version" to "0.10.0-rc.1")

    override fun init(config: Config.Scope) {}

    override fun postInit(factory: KeycloakSessionFactory) {}

    /** The last setup error logged, so a broken config is reported once, not on every login. */
    @Volatile
    private var lastSetupError: String? = null

    /**
     * Never throws. Keycloak creates the listeners enabled in a realm while it handles a
     * login, so an exception here would fail the login itself. A config error is logged
     * instead, and that session's events are dropped. Nothing is kept on failure, so the
     * next session tries again.
     */
    override fun create(session: KeycloakSession): EventListenerProvider =
        try {
            val wired = wired()
            WebhookEventListener(providerId, wired.transport, wired.filter)
        } catch (e: Exception) {
            reportSetupError(e)
            DroppingListener
        }

    private fun reportSetupError(e: Exception) {
        val message = e.message ?: e.javaClass.name
        if (message == lastSetupError) {
            LOG.debug("[{}] still not set up, event dropped: {}", providerId, message)
        } else {
            lastSetupError = message
            LOG.error("[{}] could not be set up, its events are dropped until this is fixed", providerId, e)
        }
    }

    /** Double-checked so the common path, every session after the first, is a single volatile read. */
    private fun wired(): Wiring = wiring ?: synchronized(this) {
        wiring ?: Wiring(openTransport(config), EventFilter.parse(config[eventsTakenKey])).also {
            wiring = it
            LOG.info("Opened [{}] transport", providerId)
        }
    }

    /** Server shutdown (or redeploy): the only place the transport is closed. */
    override fun close() {
        val closing = synchronized(this) { wiring.also { wiring = null } } ?: return
        runCatching { closing.transport.close() }
            .onSuccess { LOG.info("Closed [{}] transport", providerId) }
            .onFailure { LOG.warn("Error closing [{}] transport", providerId, it) }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(WebhookEventListenerFactory::class.java)
    }
}

/** Stands in for a listener whose setup failed: takes Keycloak's events and does nothing with them. */
internal object DroppingListener : EventListenerProvider {
    override fun onEvent(event: Event) {}
    override fun onEvent(event: AdminEvent, includeRepresentation: Boolean) {}
    override fun close() {}
}

/**
 * One Keycloak session's view onto the shared transport. [close] leaves the transport
 * alone on purpose: sessions end all the time, the transport stays until shutdown.
 */
internal class WebhookEventListener(
    private val providerId: String,
    private val transport: Transport,
    private val filter: EventFilter,
) : EventListenerProvider {

    override fun onEvent(event: Event) = deliver { event.toPayload() }

    override fun onEvent(event: AdminEvent, includeRepresentation: Boolean) = deliver { event.toPayload() }

    override fun close() {}

    /** Nothing may escape into Keycloak: a webhook problem must never fail a login or an admin action. */
    private inline fun deliver(payload: () -> WebhookPayload) {
        try {
            val request = payload()
            if (!filter.accepts(request.type)) {
                LOG.debug("Event {} not in the taken list. Will be skipped ({}).", request.type, filter)
                return
            }
            LOG.debug("Sending [{}] webhook for event type {}: {}", providerId, request.type, request)
            transport.publish(request)
        } catch (e: Throwable) {
            LOG.error("Could not send webhook", e)
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(WebhookEventListener::class.java)
    }
}
