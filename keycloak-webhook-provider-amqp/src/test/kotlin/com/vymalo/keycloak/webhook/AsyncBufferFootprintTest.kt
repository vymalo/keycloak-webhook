package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.models.AmqpConfig
import java.math.BigDecimal
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much heap a full async buffer takes, measured rather than guessed, so
 * WEBHOOK_AMQP_BUFFER_CAPACITY can be sized with real numbers. The broker is unreachable,
 * so every event stays buffered, built exactly as in production.
 *
 * Heap measurements are approximate; the limit only catches a change that makes buffered
 * messages several times heavier.
 */
class AsyncBufferFootprintTest {

    private val events = 100_000

    @Test
    fun `100k buffered login events`() = measure(messageId = false)

    @Test
    fun `100k buffered login events with message ids`() = measure(messageId = true)

    private fun measure(messageId: Boolean) {
        val deadPort = ServerSocket(0).use { it.localPort }
        val settings = mapOf(
            amqpHostKey to "127.0.0.1",
            amqpPortKey to deadPort.toString(),
            amqpUsernameKey to "keycloak",
            amqpPasswordKey to "secret",
            amqpExchangeKey to "keycloak",
            amqpPublishModeKey to "async",
            amqpEnablePublisherConfirm to "true",
            amqpMessageIdKey to messageId.toString(),
            amqpBufferCapacityKey to events.toString(),
        )
        val transport = AsyncAmqpTransport(AmqpConfig.from(ConfigSource { settings[it] }))
        try {
            val before = usedHeap()
            var bodyBytes = 0L
            repeat(events) { i ->
                val payload = if (i % 20 == 0) realisticAdminUpdate(i) else realisticLogin(i)
                bodyBytes += com.google.gson.Gson().toJson(payload).length
                transport.publish(payload)
            }
            val after = usedHeap()

            assertEquals(0, transport.stats.overflowDropped.get(), "everything must still be buffered")
            val perEvent = (after - before) / events
            println(
                "[buffer footprint, message ids ${if (messageId) "on" else "off"}] $events events: " +
                    "${(after - before) / 1_048_576} MB heap, $perEvent bytes per event " +
                    "(JSON bodies average ${bodyBytes / events} bytes)"
            )
            assertTrue(perEvent < 3_000, "a buffered event takes $perEvent bytes; was about 1 KB when this test was written")
        } finally {
            transport.close()
        }
    }

    /** Used heap after the collector has had a few chances to clean up; approximate by nature. */
    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(200) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun uuid() = UUID.randomUUID().toString()

    /** A login as Keycloak 26 reports it for an authorization-code flow: fresh ids everywhere, as in reality. */
    private fun realisticLogin(i: Int) = WebhookPayload(
        type = "LOGIN",
        realmId = uuid(),
        realmName = "customers",
        id = uuid(),
        time = BigDecimal(1_700_000_000_000L + i),
        clientId = "customer-portal",
        userId = uuid(),
        sessionId = uuid(),
        ipAddress = "203.0.113.${i % 250}",
        details = linkedMapOf(
            "auth_method" to "openid-connect",
            "auth_type" to "code",
            "response_type" to "code",
            "redirect_uri" to "https://portal.example.com/auth/callback?next=%2Fdashboard%2Foverview",
            "consent" to "no_consent_required",
            "code_id" to uuid(),
            "username" to "customer.$i@example.com",
            "response_mode" to "query",
            "authSessionParentId" to uuid(),
            "authSessionTabId" to "x7K2mQ9pL4w",
        ),
    )

    /** One event in twenty: an admin updating a user, carrying the user's representation (~2 KB). */
    private fun realisticAdminUpdate(i: Int) = WebhookPayload(
        type = "USER-UPDATE",
        realmId = uuid(),
        realmName = "customers",
        id = uuid(),
        time = BigDecimal(1_700_000_000_000L + i),
        clientId = uuid(),
        userId = uuid(),
        ipAddress = "198.51.100.7",
        resourcePath = "users/${uuid()}",
        representation = """{"id":"${uuid()}","username":"customer.$i@example.com","enabled":true,""" +
            """"emailVerified":true,"firstName":"Firstname$i","lastName":"Lastname$i","email":"customer.$i@example.com",""" +
            """"attributes":{"locale":["en"],"phone":["+49 30 1234567"],"department":["Sales"]},""" +
            """"groups":["/customers/premium"],"notes":"${"x".repeat(1_500)}"}""",
    )
}
