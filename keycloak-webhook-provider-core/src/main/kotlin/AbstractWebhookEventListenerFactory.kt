package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.cf
import com.vymalo.keycloak.webhook.helper.bf
import com.vymalo.keycloak.webhook.helper.eventsTakenKey
import com.vymalo.keycloak.webhook.helper.includeUserDataKey
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
    private var includeUserData: Boolean = false
    private var session: KeycloakSession? = null

    override fun getOperationalInfo() = mapOf("version" to "0.10.0-rc.1")

    companion object {
        @JvmStatic
        private val LOG = LoggerFactory.getLogger(AbstractWebhookEventListenerFactory::class.java)
    }

    override fun create(session: KeycloakSession): EventListenerProvider {
        this.session = session
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

            includeUserData = includeUserDataKey.bf()
        }
    }

    override fun init(config: Config.Scope) {}

    override fun postInit(factory: KeycloakSessionFactory) {}

    private data class UserData(
        val attributes: Map<String, List<String>>?,
        val realmRoles: List<String>?,
        val clientRoles: Map<String, List<String>>?
    )

    private fun fetchUserData(realmId: String, userId: String?): UserData {
        if (!includeUserData || userId == null || session == null) {
            return UserData(null, null, null)
        }

        return try {
            val realm = session!!.realms().getRealm(realmId)
            val user = session!!.users().getUserById(realm, userId)

            if (user == null) {
                LOG.warn("User with ID {} not found in realm {}", userId, realmId)
                return UserData(null, null, null)
            }

            // Extract user attributes, filtering out null and empty values
            val attributes = user.attributes
                ?.mapValues { (_, values) -> 
                    values.filterNotNull().filter { it.isNotBlank() }
                }
                ?.filterValues { it.isNotEmpty() }
                ?.toMap()
                ?: emptyMap()

            // Extract realm roles
            val realmRoles = user.realmRoleMappingsStream
                ?.map { it.name }
                ?.toList()
                ?: emptyList()

            // Extract client roles grouped by client ID
            val clientRoles = mutableMapOf<String, List<String>>()
            realm.clientsStream?.forEach { client ->
                val roles = user.getClientRoleMappingsStream(client)
                    ?.map { it.name }
                    ?.toList()
                    ?: emptyList()
                if (roles.isNotEmpty()) {
                    clientRoles[client.clientId] = roles
                }
            }

            UserData(
                attributes = if (attributes.isEmpty()) null else attributes,
                realmRoles = if (realmRoles.isEmpty()) null else realmRoles,
                clientRoles = if (clientRoles.isEmpty()) null else clientRoles
            )
        } catch (e: Exception) {
            LOG.error("Error fetching user data for userId: {}, realmId: {}", userId, realmId, e)
            UserData(null, null, null)
        }
    }

    override fun onEvent(event: Event) {
        val userData = fetchUserData(event.realmId, event.userId)
        send(
            event.id,
            event.time,
            event.realmId,
            event.getEventRealmName(),
            event.clientId,
            event.userId,
            event.ipAddress,
            event.type.toString(),
            event.error,
            event.details,
            null,
            null,
            userData
        )
    }

    override fun onEvent(event: AdminEvent, includeRepresentation: Boolean) {
        // For admin events, fetch user data but exclude attributes (they're in representation)
        // Only include roles
        val userData = if (includeUserData && event.authDetails?.userId != null && session != null) {
            try {
                val realm = session!!.realms().getRealm(event.realmId)
                val user = session!!.users().getUserById(realm, event.authDetails.userId)
                
                if (user != null) {
                    // Extract realm roles
                    val realmRoles = user.realmRoleMappingsStream
                        ?.map { it.name }
                        ?.toList()
                        ?: emptyList()

                    // Extract client roles grouped by client ID
                    val clientRoles = mutableMapOf<String, List<String>>()
                    realm.clientsStream?.forEach { client ->
                        val roles = user.getClientRoleMappingsStream(client)
                            ?.map { it.name }
                            ?.toList()
                            ?: emptyList()
                        if (roles.isNotEmpty()) {
                            clientRoles[client.clientId] = roles
                        }
                    }

                    UserData(
                        attributes = null,  // Exclude attributes for admin events
                        realmRoles = if (realmRoles.isEmpty()) null else realmRoles,
                        clientRoles = if (clientRoles.isEmpty()) null else clientRoles
                    )
                } else {
                    UserData(null, null, null)
                }
            } catch (e: Exception) {
                LOG.error("Error fetching user roles for admin event", e)
                UserData(null, null, null)
            }
        } else {
            UserData(null, null, null)
        }

        send(
            event.id,
            event.time,
            event.realmId,
            event.getAdminEventRealmName(),
            event.authDetails?.clientId,
            event.authDetails?.userId,
            event.authDetails?.ipAddress,
            "${event.resourceType}-${event.operationType}",
            event.error,
            null,
            event.resourcePath,
            event.representation,
            userData
        )
    }

    private fun send(
        id: String,
        time: Long?,
        realmId: String,
        realmName: String?,
        clientId: String?,
        userId: String?,
        ipAddress: String?,
        type: String,
        error: String?,
        details: Map<String, Any>?,
        resourcePath: String?,
        representation: String?,
        userData: UserData
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
            ipAddress = ipAddress,
            type = type,
            details = details,
            error = error,
            resourcePath = resourcePath,
            representation = representation,
            userAttributes = userData.attributes,
            realmRoles = userData.realmRoles,
            clientRoles = userData.clientRoles
        )

        try {
            LOG.debug("Sending [{}] webhook for event type {}: {}", delegate.getId(), type, request)
            delegate.sendWebhook(request)
        } catch (e: Throwable) {
            LOG.error("Could not send webhook", e)
        }
    }
}