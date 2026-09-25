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
import kotlin.test.assertTrue

/**
 * The same production scenarios for every AMQP setup, on the Keycloak version we run
 * ([ItSettings.keycloakVersion]). Each subclass is one setup and starts its own Keycloak
 * and RabbitMQ, so setups never share state and can be run on their own.
 *
 * Scenarios run in order: the last one stops Keycloak to check the shutdown path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class AmqpScenarios(private val settings: Map<String, String>) {

    /** True when every event must arrive at least once even if the broker hangs for a while. */
    protected abstract val survivesBrokerHang: Boolean

    /** True when a healthy run must deliver each event exactly once (no resends happen). */
    protected abstract val exactlyOnceWhenHealthy: Boolean

    private lateinit var stack: Stack
    private lateinit var api: KeycloakApi
    private lateinit var realmId: String

    private val realm = "webhook-it"
    private val users = 200

    @BeforeAll
    fun start() {
        stack = Stack(ItSettings.keycloakVersion, settings).start()
        api = KeycloakApi(stack.keycloakUrl)
        api.createRealm(KeycloakApi.testRealm(realm, users))
        realmId = api.realmId(realm)
    }

    @AfterAll
    fun stop() = stack.close()

    @Test
    @Order(1)
    fun `real logins, failed logins and admin actions arrive with real content`() {
        Observer(stack).use { observer ->
            assertTrue(api.passwordLogin(realm, "user1").ok)
            assertEquals(401, api.passwordLogin(realm, "user1", "wrong").status)
            api.createUser(realm, "created-${UUID.randomUUID()}")

            assertTrue(observer.awaitDistinct(3, 30_000), "got ${observer.messages.map { it.type }}")
            val byType = observer.messages.associateBy { it.type }

            val login = assertNotNull(byType["LOGIN"])
            val userId = login.payload["userId"].asString
            assertEquals("KC_CLIENT.$realmId.${KeycloakApi.CLIENT_ID}.$userId.LOGIN", login.routingKey)
            assertEquals(realm, login.payload["realmName"]?.asString)
            assertNotNull(login.payload["sessionId"], "a login belongs to a session")
            assertEquals("user1", login.username)

            val failed = assertNotNull(byType["LOGIN_ERROR"])
            assertEquals("invalid_user_credentials", failed.payload["error"].asString)

            val admin = assertNotNull(byType["USER-CREATE"])
            assertTrue(admin.routingKey.startsWith("KC_CLIENT.$realmId.") && admin.routingKey.endsWith(".USER-CREATE"), admin.routingKey)
            assertTrue(admin.payload["resourcePath"].asString.startsWith("users/"))
        }
    }

    @Test
    @Order(2)
    fun `one broker connection serves every session`() {
        val report = Traffic(api, realm, users).logins(50) { "user${it + 1}" }
        assertEquals(0, report.failedRequests)
        assertEquals(1, stack.pluginConnections(), "plugin connections after 50 logins")
    }

    @Test
    @Order(3)
    fun `logins keep working while the broker hangs`() {
        Observer(stack).use { observer ->
            assertTrue(api.passwordLogin(realm, "user1").ok) // the connection is up before the broker hangs
            val hanging = (11..20).map { "user$it" }

            stack.pauseBroker()
            val report = try {
                Traffic(api, realm, users).logins(hanging.size) { hanging[it] }
            } finally {
                stack.unpauseBroker()
            }
            println("[${javaClass.simpleName}] logins while the broker hangs: $report")
            assertEquals(0, report.failedRequests, "every login must still succeed")

            if (survivesBrokerHang) {
                assertTrue(report.percentile(0.95) < 2_000, "logins shouldn't wait for the broker: $report")
                val arrived = observer.awaitCondition(120_000) {
                    observer.messages.mapNotNull { it.username }.containsAll(hanging)
                }
                assertTrue(arrived, "logins from the outage that arrived: ${observer.messages.mapNotNull { it.username }.toSet()}")
            }
        }
    }

    @Test
    @Order(4)
    fun `organic traffic arrives complete`() {
        Observer(stack).use { observer ->
            val report = Traffic(api, realm, users).run(ItSettings.events, ItSettings.concurrency)
            println("[${javaClass.simpleName}] sent: $report")
            assertEquals(0, report.failedRequests, "Keycloak itself should have answered every request")

            val complete = observer.awaitDistinct(report.expectedEvents, 600_000)
            val arrived = observer.distinctByType()
            println(
                "[${javaClass.simpleName}] arrived: ${observer.distinctEvents} distinct events, " +
                    "${observer.duplicates} duplicates; by type: $arrived"
            )
            assertTrue(complete, "only ${observer.distinctEvents} of ${report.expectedEvents} events arrived")
            report.expectedByType.forEach { (type, count) ->
                assertEquals(count, arrived[type] ?: 0, "distinct $type events")
            }
            if (exactlyOnceWhenHealthy) assertEquals(0, observer.duplicates, "duplicates on a healthy broker")
            assertTrue(observer.awaitCondition(30_000) { observer.backlog() == 0L }, "the broker should be fully drained")
        }
    }

    /**
     * A broker that hangs for minutes under steady traffic: the case that decides between the
     * setups in production. Every setup must recover afterwards; only one that promises to survive
     * a hang must also keep logins fast and deliver everything. The numbers are printed either way.
     */
    @Test
    @Order(5)
    fun `a long broker outage under steady traffic`() {
        val name = javaClass.simpleName
        Observer(stack).use { observer ->
            val leadInMs = 10_000L
            val outageMs = ItSettings.outageSeconds * 1_000
            val tailMs = 20_000L

            val traffic = Traffic(api, realm, users)
            var report: Traffic.Report? = null
            val driver = Thread {
                report = traffic.runFor(leadInMs + outageMs + tailMs, concurrency = 8, perSecond = ItSettings.outageRate)
            }
            driver.start()

            Thread.sleep(leadInMs)
            val pausedAt = System.currentTimeMillis()
            stack.pauseBroker()
            try {
                Thread.sleep(outageMs)
            } finally {
                stack.unpauseBroker()
            }
            val resumedAt = System.currentTimeMillis()
            driver.join()
            val sent = assertNotNull(report)

            println("[$name] long outage, ${ItSettings.outageSeconds}s at ${ItSettings.outageRate} requests/s: $sent")
            println("[$name]   requests started before the outage: ${sent.window(0, pausedAt)}")
            println("[$name]   requests started during the outage: ${sent.window(pausedAt, resumedAt)}")
            println("[$name]   requests started after the outage:  ${sent.window(resumedAt, Long.MAX_VALUE)}")

            // Whatever the setup promises, Keycloak and the plugin must come back on their own.
            assertTrue(api.passwordLogin(realm, "user199").ok, "Keycloak should answer again after the outage")
            val recovered = observer.awaitCondition(180_000) { observer.messages.any { it.username == "user199" } }
            observer.awaitCondition(60_000) { observer.distinctEvents >= sent.expectedEvents + 1 }
            println(
                "[$name]   arrived: ${observer.distinctEvents} of ${sent.expectedEvents + 1} events " +
                    "(${observer.duplicates} duplicates); recovered: $recovered"
            )
            assertTrue(recovered, "events should flow again after the outage")

            if (survivesBrokerHang) {
                assertEquals(0, sent.failedRequests, "no request may fail while the broker hangs")
                val duringP99 = sent.samples.filter { it.startedAtMs in pausedAt until resumedAt }
                    .map { it.latencyMs }.sorted().let { it[((it.size - 1) * 0.99).toInt()] }
                assertTrue(duringP99 < 2_000, "p99 during the outage was ${duringP99}ms")
                assertTrue(observer.distinctEvents >= sent.expectedEvents + 1, "every event should arrive after the outage")
            }
        }
    }

    @Test
    @Order(6)
    fun `a graceful shutdown closes the transport and loses nothing`() {
        Observer(stack).use { observer ->
            val burst = Traffic(api, realm, users).run(2_000, ItSettings.concurrency)
            val logs = stack.stopKeycloakGracefully()

            assertTrue("Closed [webhook-amqp] transport" in logs, "Keycloak never closed the plugin's transport")
            assertTrue(observer.awaitDistinct(burst.expectedEvents, 60_000),
                "only ${observer.distinctEvents} of ${burst.expectedEvents} events arrived")
            onShutdownLogs(logs)
        }
    }

    /** Setup-specific checks on what Keycloak logged while stopping. */
    protected open fun onShutdownLogs(logs: String) {}
}

/**
 * Upstream's default today: sync publishing without confirms. A message counts as sent once it's
 * written to the socket, so nothing is promised for events sent while the broker hangs.
 */
class SyncDefaultsTest : AmqpScenarios(emptyMap()) {
    override val survivesBrokerHang = false
    override val exactlyOnceWhenHealthy = true
}

/** Sync publishing on the request thread, waiting for each confirm: the conservative production setup. */
class SyncWithConfirmsTest : AmqpScenarios(
    mapOf("WEBHOOK_AMQP_ENABLE_PUBLISHER_CONFIRM" to "true")
) {
    override val survivesBrokerHang = false
    override val exactlyOnceWhenHealthy = true
}

/**
 * Async publishing without confirms: logins never wait, but a message counts as sent once it's
 * written to the connection, so nothing is resent. Here to show what confirms add, not as a
 * recommended setup.
 */
class AsyncWithoutConfirmsTest : AmqpScenarios(
    mapOf(
        "WEBHOOK_AMQP_PUBLISH_MODE" to "async",
        "WEBHOOK_AMQP_MESSAGE_ID" to "true",
        "WEBHOOK_AMQP_BUFFER_CAPACITY" to "10000",
    )
) {
    override val survivesBrokerHang = false
    override val exactlyOnceWhenHealthy = true
}

/** Async publishing with confirms: at least once, with ids to drop duplicates, and a buffer for bursts. */
class AsyncAtLeastOnceTest : AmqpScenarios(
    mapOf(
        "WEBHOOK_AMQP_PUBLISH_MODE" to "async",
        "WEBHOOK_AMQP_ENABLE_PUBLISHER_CONFIRM" to "true",
        "WEBHOOK_AMQP_MESSAGE_ID" to "true",
        "WEBHOOK_AMQP_BUFFER_CAPACITY" to "10000",
    )
) {
    override val survivesBrokerHang = true
    override val exactlyOnceWhenHealthy = false

    /** The summary counts drops over the whole run; what matters here is that the drain left nothing behind. */
    override fun onShutdownLogs(logs: String) {
        assertNotNull(logs.lines().lastOrNull { "AMQP publisher stopped:" in it }, "the async publisher never logged its shutdown")
        val leftBehind = logs.lines().firstOrNull { "undelivered messages" in it }
        assertTrue(leftBehind == null, "shutdown left messages behind: $leftBehind")
    }
}
