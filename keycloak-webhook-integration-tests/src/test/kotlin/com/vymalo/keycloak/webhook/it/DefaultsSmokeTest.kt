package com.vymalo.keycloak.webhook.it

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every Keycloak version the README promises, with none of the opt-in settings: the plugin
 * loads, real events arrive, one broker connection serves all sessions, and Keycloak closes
 * the transport on shutdown. That also shows the defaults behave the same on each version.
 */
class DefaultsSmokeTest {

    companion object {
        @JvmStatic
        fun versions() = ItSettings.keycloakVersions
    }

    @ParameterizedTest(name = "Keycloak {0}")
    @MethodSource("versions")
    fun `the plugin works with default settings`(version: String) {
        Stack(version, emptyMap()).start().use { stack ->
            val api = KeycloakApi(stack.keycloakUrl)
            val realm = "smoke"
            api.createRealm(KeycloakApi.testRealm(realm, users = 20))
            val realmId = api.realmId(realm)

            val listener = api.serverInfo().getAsJsonObject("providers")
                .getAsJsonObject("eventsListener").getAsJsonObject("providers").getAsJsonObject("webhook-amqp")
            assertNotNull(listener, "Keycloak $version didn't load webhook-amqp")
            assertNotNull(listener.getAsJsonObject("operationalInfo")?.get("version"), "operational info")

            Observer(stack).use { observer ->
                assertTrue(api.passwordLogin(realm, "user1").ok)
                assertEquals(401, api.passwordLogin(realm, "user1", "wrong").status)
                api.createUser(realm, "created-${UUID.randomUUID()}")
                assertTrue(observer.awaitDistinct(3, 30_000), "got ${observer.messages.map { it.type }}")

                val login = observer.messages.first { it.type == "LOGIN" }
                assertEquals("KC_CLIENT.$realmId.${KeycloakApi.CLIENT_ID}.${login.payload["userId"].asString}.LOGIN", login.routingKey)
                // getRealmName() only exists since Keycloak 25; before that the field is left out.
                if (version.substringBefore('.').toInt() >= 25) assertEquals(realm, login.payload["realmName"]?.asString)
                else assertNull(login.payload["realmName"])
                assertTrue(observer.messages.any { it.type == "LOGIN_ERROR" })
                assertTrue(observer.messages.any { it.type == "USER-CREATE" })
                // Defaults: transient messages without ids, as always.
                assertNull(login.messageId)
            }

            Traffic(api, realm, users = 20).logins(20) { "user${it + 1}" }
            assertEquals(1, stack.pluginConnections(), "plugin connections after 20 logins")

            val logs = stack.stopKeycloakGracefully()
            assertTrue("Closed [webhook-amqp] transport" in logs, "Keycloak $version never closed the transport")
        }
    }
}
