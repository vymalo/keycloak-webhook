package com.vymalo.keycloak.webhook.testing

import org.keycloak.events.Event
import org.keycloak.events.EventType
import org.keycloak.events.admin.AdminEvent
import org.keycloak.events.admin.AuthDetails
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType

/**
 * Sample Keycloak events and what each transport is expected to send for them.
 *
 * The golden strings are the behaviour contract of the plugin: they were captured
 * from the upstream code before the rework, and every later change must keep
 * producing them byte for byte unless a new, opt-in setting says otherwise.
 */
object Fixtures {

    const val REALM_ID = "realm-id"

    /** A regular login with every optional field filled, so nothing is hidden by a null. */
    fun loginEvent(): Event = Event().apply {
        id = "evt-1"
        time = 1_700_000_000_000
        type = EventType.LOGIN
        realmId = REALM_ID
        realmName = "realm-name"
        clientId = "account"
        userId = "user-1"
        sessionId = "session-1"
        ipAddress = "10.0.0.1"
        // Keycloak keeps details in insertion order; a LinkedHashMap keeps the golden JSON stable.
        details = linkedMapOf("username" to "alice", "auth_method" to "openid-connect")
    }

    /** A login without client or user, which exercises the `xxx` placeholders in AMQP routing keys. */
    fun anonymousEvent(): Event = Event().apply {
        id = "evt-2"
        time = 1_700_000_000_002
        type = EventType.LOGIN_ERROR
        realmId = REALM_ID
        error = "user_not_found"
    }

    /** An admin creating a user. Admin events have no session id and carry the resource instead of details. */
    fun adminEvent(): AdminEvent = AdminEvent().apply {
        id = "adm-1"
        time = 1_700_000_000_001
        realmId = REALM_ID
        realmName = "realm-name"
        authDetails = AuthDetails().apply {
            realmId = REALM_ID
            clientId = "admin-cli"
            userId = "admin-1"
            ipAddress = "10.0.0.2"
        }
        resourceType = ResourceType.USER
        operationType = OperationType.CREATE
        resourcePath = "users/user-2"
        representation = """{"username":"bob"}"""
    }

    /** `WebhookPayload` as Gson writes it (AMQP and Syslog bodies). */
    object PayloadJson {
        const val LOGIN =
            """{"type":"LOGIN","realmId":"realm-id","realmName":"realm-name","id":"evt-1","time":1700000000000,""" +
                """"clientId":"account","userId":"user-1","sessionId":"session-1","ipAddress":"10.0.0.1",""" +
                """"details":{"username":"alice","auth_method":"openid-connect"}}"""

        const val ANONYMOUS =
            """{"type":"LOGIN_ERROR","realmId":"realm-id","id":"evt-2","time":1700000000002,"error":"user_not_found"}"""

        const val ADMIN =
            """{"type":"USER-CREATE","realmId":"realm-id","realmName":"realm-name","id":"adm-1","time":1700000000001,""" +
                """"clientId":"admin-cli","userId":"admin-1","ipAddress":"10.0.0.2","resourcePath":"users/user-2",""" +
                """"representation":"{\"username\":\"bob\"}"}"""
    }
}
