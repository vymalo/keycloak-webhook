package com.vymalo.keycloak.webhook.it

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP provider as it works today, end to end: Keycloak sends every event to two URLs, as
 * WEBHOOK_HTTP_BASE_PATH allows. One is Prism checking each request against the plugin's OpenAPI
 * spec (upstream's compose.yaml uses the same mock); the other records exactly what was sent and can
 * be made to fail. Nothing here asks for changed behaviour: disruptions are measured and printed,
 * and only recovery is required.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HttpTest {

    private val name = javaClass.simpleName
    private val realm = "webhook-it"
    private val users = 200

    private lateinit var recorder: HttpRecorder
    private lateinit var prism: Stack.Container
    private lateinit var stack: Stack
    private lateinit var api: KeycloakApi
    private lateinit var realmId: String

    @BeforeAll
    fun start() {
        recorder = HttpRecorder() // before Keycloak starts, so host access to it is set up in time
        val settings = mapOf(
            "WEBHOOK_HTTP_BASE_PATH" to "http://prism:4010,${recorder.urlForKeycloak}",
            "WEBHOOK_HTTP_AUTH_USERNAME" to "admin",
            "WEBHOOK_HTTP_AUTH_PASSWORD" to "password",
        )
        stack = Stack(ItSettings.keycloakVersion, settings, Topology.NONE) { network -> listOf(prismMock(network).also { prism = it }) }.start()
        api = KeycloakApi(stack.keycloakUrl)
        api.createRealm(KeycloakApi.testRealm(realm, users, listeners = listOf("webhook-http")))
        realmId = api.realmId(realm)
    }

    @AfterAll
    fun stop() {
        stack.close()
        recorder.close()
    }

    private fun prismVerdict() = PrismVerdict(prism.logs)

    @Test
    @Order(1)
    fun `real events reach every URL with the documented body and basic auth`() {
        recorder.clear()
        val prismBefore = prismVerdict().received
        assertTrue(api.passwordLogin(realm, "user1").ok)
        assertEquals(401, api.passwordLogin(realm, "user1", "wrong").status)
        api.createUser(realm, "created-${UUID.randomUUID()}")

        await("3 events at the recorder") { recorder.requests.map { it.eventId }.toSet().size >= 3 }
        val login = recorder.requests.first { it.type == "LOGIN" }
        assertEquals("POST", login.method)
        assertEquals("/", login.path)
        assertEquals("Basic YWRtaW46cGFzc3dvcmQ=", login.authorization)
        assertTrue(login.contentType!!.startsWith("application/json"), login.contentType)
        assertEquals(realmId, login.body["realmId"].asString)
        assertEquals("user1", login.username)
        assertNotNull(login.body["sessionId"], "sessionId is in the spec since #90")
        // The body is the OpenAPI WebhookRequest, which has no realmName (the AMQP and Syslog payloads do).
        assertNull(login.body["realmName"])
        assertEquals("invalid_user_credentials", recorder.requests.first { it.type == "LOGIN_ERROR" }.body["error"].asString)
        assertTrue(recorder.requests.first { it.type == "USER-CREATE" }.body["resourcePath"].asString.startsWith("users/"))

        await("3 requests at Prism") { prismVerdict().received - prismBefore >= 3 }
        val verdict = prismVerdict()
        assertTrue(verdict.violations.isEmpty(), "Prism found requests that break the spec: $verdict")
    }

    @Test
    @Order(2)
    fun `organic traffic arrives complete at every URL`() {
        recorder.clear()
        val prismBefore = prismVerdict().received
        val sent = Traffic(api, realm, users).run(ItSettings.events, ItSettings.concurrency)
        println("[$name] sent: $sent")
        assertEquals(0, sent.failedRequests, "Keycloak itself should have answered every request")

        await("every event at the recorder", 600_000) { recorder.distinctByType().values.sum() >= sent.expectedEvents }
        val arrived = recorder.distinctByType()
        println("[$name] arrived at the recorder: $arrived")
        sent.expectedByType.forEach { (type, count) -> assertEquals(count, arrived[type] ?: 0, "distinct $type events") }

        await("every event at Prism", 600_000) { prismVerdict().received - prismBefore >= sent.expectedEvents }
        val verdict = prismVerdict()
        println("[$name] Prism: $verdict")
        assertTrue(verdict.violations.isEmpty(), "Prism found requests that break the spec: $verdict")
    }

    @Test
    @Order(3)
    fun `a receiver that hangs holds every login until the client gives up`() =
        disrupted("second URL hangs for 30s") {
            recorder.mode = HttpRecorder.Mode.HANG
            Thread.sleep(30_000)
            recorder.mode = HttpRecorder.Mode.OK
        }

    @Test
    @Order(4)
    fun `a receiver that is gone adds the retry pauses to every login`() =
        disrupted("second URL drops every connection for 30s") {
            recorder.mode = HttpRecorder.Mode.DROP
            Thread.sleep(30_000)
            recorder.mode = HttpRecorder.Mode.OK
        }

    @Test
    @Order(100)
    fun `a graceful shutdown closes the transport`() {
        val logs = stack.stopKeycloakGracefully()
        assertTrue("Closed [webhook-http] transport" in logs, "Keycloak never closed the plugin's transport")
    }

    /**
     * Today's HTTP provider sends on the request thread, URL after URL, with 3 attempts a second apart.
     * So these runs measure what a bad receiver costs every login; they only require that everything
     * works again afterwards.
     */
    private fun disrupted(label: String, disruption: () -> Unit) {
        val run = Traffic(api, realm, users).around(disruption)
        run.print(name, "$label at ${ItSettings.outageRate} requests/s")
        recorder.clear()
        assertTrue(api.passwordLogin(realm, "user199").ok, "Keycloak should answer again afterwards")
        await("events flow again") { recorder.requests.any { it.username == "user199" } }
    }

    private fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(200)
        }
    }
}
