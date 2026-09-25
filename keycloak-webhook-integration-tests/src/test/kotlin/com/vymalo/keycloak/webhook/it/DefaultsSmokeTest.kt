package com.vymalo.keycloak.webhook.it

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every Keycloak version the README promises, with none of the opt-in settings and all three
 * providers enabled at once: each loads, real events reach RabbitMQ, the HTTP receiver and
 * syslog-ng, one broker connection serves all sessions, and Keycloak closes every transport on
 * shutdown. That also shows the defaults behave the same on each version.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultsSmokeTest {

    companion object {
        @JvmStatic
        fun versions() = ItSettings.keycloakVersions

        private val providers = listOf("webhook-amqp", "webhook-http", "webhook-syslog")
    }

    /** One receiver for every version: it's in the test JVM and outlives the Keycloaks. */
    private val recorder = HttpRecorder()

    @AfterAll
    fun stopRecorder() = recorder.close()

    @ParameterizedTest(name = "Keycloak {0}")
    @MethodSource("versions")
    fun `every provider works with default settings`(version: String) {
        recorder.clear()
        val settings = mapOf("WEBHOOK_HTTP_BASE_PATH" to recorder.urlForKeycloak) + SyslogServer.pluginSettings("udp")
        lateinit var syslog: Stack.Container
        Stack(version, settings, Topology.SINGLE) { network -> listOf(SyslogServer.container(network).also { syslog = it }) }
            .start().use { stack ->
                val api = KeycloakApi(stack.keycloakUrl)
                val realm = "smoke"
                api.createRealm(KeycloakApi.testRealm(realm, users = 20, listeners = providers))
                val realmId = api.realmId(realm)

                val loaded = api.serverInfo().getAsJsonObject("providers")
                    .getAsJsonObject("eventsListener").getAsJsonObject("providers")
                providers.forEach { id ->
                    val listener = assertNotNull(loaded.getAsJsonObject(id), "Keycloak $version didn't load $id")
                    assertNotNull(listener.getAsJsonObject("operationalInfo")?.get("version"), "$id operational info")
                }

                Observer(stack).use { observer ->
                    assertTrue(api.passwordLogin(realm, "user1").ok)
                    assertEquals(401, api.passwordLogin(realm, "user1", "wrong").status)
                    api.createUser(realm, "created-${UUID.randomUUID()}")
                    assertTrue(observer.awaitDistinct(3, 30_000), "AMQP got ${observer.messages.map { it.type }}")

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

                await("the same events over HTTP") { recorder.requests.map { it.type }.containsAll(listOf("LOGIN", "LOGIN_ERROR", "USER-CREATE")) }
                await("the same events at syslog-ng") {
                    SyslogServer.distinctByType(syslog).keys.containsAll(listOf("LOGIN", "LOGIN_ERROR", "USER-CREATE"))
                }

                Traffic(api, realm, users = 20).logins(20) { "user${it + 1}" }
                assertEquals(1, stack.awaitPluginConnections(1), "AMQP connections after 20 logins; ${stack.describeConnections()}")

                val logs = stack.stopKeycloakGracefully()
                providers.forEach { id -> assertTrue("Closed [$id] transport" in logs, "Keycloak $version never closed $id") }
            }
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(300)
        }
    }
}
