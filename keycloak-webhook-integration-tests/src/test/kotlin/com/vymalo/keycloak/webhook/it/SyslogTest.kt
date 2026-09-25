package com.vymalo.keycloak.webhook.it

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Syslog provider as it works today, end to end, against syslog-ng set up the way upstream's
 * compose.yaml runs it. syslog-ng also keeps every message exactly as it arrived, so the wire format
 * is checked field by field. Disruptions are measured and printed; only recovery is required.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class SyslogScenarios(private val protocol: String) {

    private val name = javaClass.simpleName
    private val realm = "webhook-it"
    private val users = 200

    private lateinit var syslog: Stack.Container
    private lateinit var stack: Stack
    private lateinit var api: KeycloakApi

    /** `<PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG`, as RFC 5424 lays it out. */
    private val rfc5424 = Regex("""^<(\d+)>1 (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\{.*})$""")

    @BeforeAll
    fun start() {
        stack = Stack(ItSettings.keycloakVersion, SyslogServer.pluginSettings(protocol), Topology.NONE) { network ->
            listOf(SyslogServer.container(network).also { syslog = it })
        }.start()
        api = KeycloakApi(stack.keycloakUrl)
        api.createRealm(KeycloakApi.testRealm(realm, users, listeners = listOf("webhook-syslog")))
    }

    @AfterAll
    fun stop() = stack.close()

    /** The raw message of the first event of [type] that arrived, split into its RFC 5424 fields. */
    private fun firstOf(type: String): MatchResult {
        val line = SyslogServer.rawMessages(syslog).firstOrNull { "\"type\":\"$type\"" in it }
        return assertNotNull(line?.let { rfc5424.find(it) }, "no well-formed $type message; got: $line")
    }

    @Test
    @Order(1)
    fun `real events arrive as syslog messages with the event as JSON body`() {
        SyslogServer.clear(syslog)
        assertTrue(api.passwordLogin(realm, "user1").ok)
        assertEquals(401, api.passwordLogin(realm, "user1", "wrong").status)
        api.createUser(realm, "created-${UUID.randomUUID()}")

        await("3 events at syslog-ng") { SyslogServer.distinctByType(syslog).values.sum() >= 3 }
        val login = firstOf("LOGIN")
        // <14>: facility USER (1) x 8 + severity INFORMATIONAL (6)
        assertEquals("14", login.groupValues[1])
        assertEquals("keycloak_events", login.groupValues[4])
        assertTrue("\"sessionId\":\"" in login.groupValues[8], "the payload carries the session")
        assertTrue("\"username\":\"user1\"" in login.groupValues[8])
        firstOf("LOGIN_ERROR")
        firstOf("USER-CREATE")
        // syslog-ng parsed and stored them too, i.e. they're valid syslog, not just bytes.
        assertTrue(SyslogServer.parsedCount(syslog) >= 3, "syslog-ng accepted ${SyslogServer.parsedCount(syslog)} messages")
    }

    /**
     * Known upstream bug, kept here so it stays visible: the HOSTNAME field should name the Keycloak
     * that sent the message (WEBHOOK_SYSLOG_HOSTNAME, as the README says), but carries the syslog
     * server's own name. Remove @Disabled with the fix.
     */
    @Test
    @Order(2)
    @Disabled("Known bug: HOSTNAME carries WEBHOOK_SYSLOG_SERVER_HOSTNAME instead of WEBHOOK_SYSLOG_HOSTNAME; fixed later in this PR")
    fun `the HOSTNAME field names the Keycloak that sent the message`() {
        assertTrue(api.passwordLogin(realm, "user2").ok)
        await("a login at syslog-ng") { SyslogServer.rawMessages(syslog).any { "\"username\":\"user2\"" in it } }
        assertEquals("keycloak-it", firstOf("LOGIN").groupValues[3])
    }

    @Test
    @Order(3)
    fun `organic traffic arrives complete`() {
        SyslogServer.clear(syslog)
        val sent = Traffic(api, realm, users).run(ItSettings.events, ItSettings.concurrency)
        println("[$name] sent: $sent")
        assertEquals(0, sent.failedRequests, "Keycloak itself should have answered every request")

        val complete = runCatching {
            await("every event at syslog-ng", 600_000) { SyslogServer.distinctByType(syslog).values.sum() >= sent.expectedEvents }
        }.isSuccess
        val arrived = SyslogServer.distinctByType(syslog)
        println("[$name] arrived: ${arrived.values.sum()} of ${sent.expectedEvents}; by type: $arrived")
        assertTrue(complete, "only ${arrived.values.sum()} of ${sent.expectedEvents} events arrived")
        sent.expectedByType.forEach { (type, count) -> assertEquals(count, arrived[type] ?: 0, "distinct $type events") }
    }

    @Test
    @Order(4)
    fun `the syslog server hanging for a while`() = disrupted("syslog-ng hangs for 30s") {
        syslog.dockerClient.pauseContainerCmd(syslog.containerId).exec()
        try {
            Thread.sleep(30_000)
        } finally {
            syslog.dockerClient.unpauseContainerCmd(syslog.containerId).exec()
        }
    }

    @Test
    @Order(5)
    fun `the syslog server down for a while`() = disrupted("syslog-ng down for 30s") {
        syslog.dockerClient.stopContainerCmd(syslog.containerId).withTimeout(10).exec()
        Thread.sleep(30_000)
        syslog.dockerClient.startContainerCmd(syslog.containerId).exec()
        await("syslog-ng back", 120_000) {
            runCatching { syslog.execInContainer("sh", "-c", "pgrep syslog-ng").exitCode == 0 }.getOrDefault(false)
        }
    }

    @Test
    @Order(100)
    fun `a graceful shutdown closes the transport`() {
        val logs = stack.stopKeycloakGracefully()
        assertTrue("Closed [webhook-syslog] transport" in logs, "Keycloak never closed the plugin's transport")
    }

    /**
     * Measures what a misbehaving syslog server costs every login and how many events it loses, and
     * only requires that events flow again afterwards.
     */
    private fun disrupted(label: String, disruption: () -> Unit) {
        SyslogServer.clear(syslog)
        val run = Traffic(api, realm, users).around(disruption)
        run.print(name, "$label at ${ItSettings.outageRate} requests/s")

        assertTrue(api.passwordLogin(realm, "user199").ok, "Keycloak should answer again afterwards")
        val recovered = runCatching {
            await("events flow again", 120_000) { SyslogServer.rawMessages(syslog).any { "\"username\":\"user199\"" in it } }
        }.isSuccess
        val arrived = SyslogServer.distinctByType(syslog).values.sum()
        println("[$name]   arrived: $arrived of ${run.report.expectedEvents + 1} events; recovered: $recovered")
        assertTrue(recovered, "events should flow again afterwards")
    }

    private fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what\n${diagnostics()}" }
            Thread.sleep(500)
        }
    }

    /** What syslog-ng has, and what it said about itself, for a failure message that explains itself. */
    private fun diagnostics(): String {
        val raw = runCatching { SyslogServer.rawMessages(syslog) }.getOrDefault(emptyList())
        return "syslog-ng stored ${raw.size} raw messages (first: ${raw.firstOrNull()?.take(200)}), " +
            "${runCatching { SyslogServer.parsedCount(syslog) }.getOrDefault(-1)} parsed; its log ends with:\n" +
            syslog.logs.lines().takeLast(15).joinToString("\n")
    }
}

/** UDP with RFC 5424, as upstream's compose.yaml sends it. */
class SyslogUdpTest : SyslogScenarios("udp")

/**
 * TCP with the default RFC 5425 framing (octet counting).
 *
 * Known upstream bug, kept here so it stays visible: the sender writes CRLF after every
 * octet-counted frame. syslog-ng reads that as the next frame's header, rejects it, and closes the
 * connection, so every other message or so is lost. Remove @Disabled with the fix.
 */
@Disabled("Known bug: CRLF after RFC 5425 octet-counted frames makes syslog-ng drop the connection; fixed later in this PR")
class SyslogTcpTest : SyslogScenarios("tcp")
