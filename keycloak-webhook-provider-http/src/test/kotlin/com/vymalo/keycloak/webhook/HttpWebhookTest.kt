package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.httpAuthPasswordKey
import com.vymalo.keycloak.webhook.helper.httpAuthUsernameKey
import com.vymalo.keycloak.webhook.helper.httpBaseBathKey
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.withConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the HTTP provider sends, checked against local mock servers: method,
 * path, auth header and the JSON body produced by the OpenAPI client.
 */
class HttpWebhookTest {

    private val servers = mutableListOf<MockWebServer>()

    private fun server(vararg responses: Int): MockWebServer = MockWebServer().also { server ->
        responses.forEach { server.enqueue(MockResponse().setResponseCode(it)) }
        server.start()
        servers += server
    }

    @AfterEach
    fun stopServers() = servers.forEach { it.shutdown() }

    /** Base URLs are used as given, so they must not end in a slash to hit `/` exactly. */
    private fun MockWebServer.baseUrl() = url("/").toString().removeSuffix("/")

    private fun MockWebServer.next(): RecordedRequest? = takeRequest(5, TimeUnit.SECONDS)

    private fun <T> withEndpoints(vararg targets: MockWebServer, block: () -> T): T = withConfig(
        httpBaseBathKey to targets.joinToString(",") { it.baseUrl() },
        httpAuthUsernameKey to "admin",
        httpAuthPasswordKey to "password",
        block = block,
    )

    @Test
    fun `user event is posted as JSON with basic auth`() {
        val endpoint = server(200)
        withEndpoints(endpoint) { HttpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) } }

        val request = assertNotNull(endpoint.next())
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        assertEquals("Basic YWRtaW46cGFzc3dvcmQ=", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(HttpJson.LOGIN, request.body.readUtf8())
    }

    @Test
    fun `admin event body carries resource path and representation`() {
        val endpoint = server(200)
        withEndpoints(endpoint) { HttpWebhookFactory().inSession { it.onEvent(Fixtures.adminEvent(), true) } }

        assertEquals(HttpJson.ADMIN, assertNotNull(endpoint.next()).body.readUtf8())
    }

    @Test
    fun `every configured base URL receives the event`() {
        val first = server(200)
        val second = server(200)
        withEndpoints(first, second) { HttpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) } }

        assertEquals(HttpJson.LOGIN, assertNotNull(first.next()).body.readUtf8())
        assertEquals(HttpJson.LOGIN, assertNotNull(second.next()).body.readUtf8())
    }

    @Test
    fun `server errors are retried up to three attempts in total`() {
        val flaky = server(500, 500, 200)
        withEndpoints(flaky) { HttpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) } }

        repeat(3) { assertNotNull(flaky.next(), "attempt ${it + 1}") }
    }

    @Test
    fun `a dead endpoint is given up on after three attempts without failing Keycloak`() {
        val dead = server(500, 500, 500, 500)
        withEndpoints(dead) { HttpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) } }

        repeat(3) { assertNotNull(dead.next(), "attempt ${it + 1}") }
        assertNull(dead.takeRequest(200, TimeUnit.MILLISECONDS), "no fourth attempt")
    }

    /**
     * The HTTP body comes from the generated OpenAPI model, not from `WebhookPayload`:
     * fields follow the schema order (`sessionId` last, as it was added last) and there
     * is no `realmName`.
     */
    private object HttpJson {
        const val LOGIN =
            """{"type":"LOGIN","realmId":"realm-id","id":"evt-1","time":1700000000000,"clientId":"account",""" +
                """"userId":"user-1","ipAddress":"10.0.0.1",""" +
                """"details":{"username":"alice","auth_method":"openid-connect"},"sessionId":"session-1"}"""

        const val ADMIN =
            """{"type":"USER-CREATE","realmId":"realm-id","id":"adm-1","time":1700000000001,"clientId":"admin-cli",""" +
                """"userId":"admin-1","ipAddress":"10.0.0.2","resourcePath":"users/user-2",""" +
                """"representation":"{\"username\":\"bob\"}"}"""
    }
}
