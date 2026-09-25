package com.vymalo.keycloak.webhook.it

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** One way of configuring the plugin's AMQP publishing, and what that setup promises. */
enum class Variant(
    val settings: Map<String, String>,
    /** Every event arrives at least once, even across broker hangs and node failures, and logins don't wait. */
    val atLeastOnce: Boolean,
    /** A healthy broker gets each event exactly once (nothing is ever resent). */
    val exactlyOnceWhenHealthy: Boolean,
) {
    /** Upstream's default: sync on the request thread, no confirms. */
    SYNC(emptyMap(), atLeastOnce = false, exactlyOnceWhenHealthy = true),

    /** Sync, waiting for each confirm. */
    SYNC_CONFIRMS(mapOf("WEBHOOK_AMQP_ENABLE_PUBLISHER_CONFIRM" to "true"), atLeastOnce = false, exactlyOnceWhenHealthy = true),

    /** Async without confirms: here to show what confirms add, not as a recommended setup. */
    ASYNC(
        mapOf(
            "WEBHOOK_AMQP_PUBLISH_MODE" to "async",
            "WEBHOOK_AMQP_MESSAGE_ID" to "true",
            "WEBHOOK_AMQP_BUFFER_CAPACITY" to "10000",
        ),
        atLeastOnce = false, exactlyOnceWhenHealthy = true,
    ),

    /** Async with confirms: at least once, with ids to drop duplicates, and a buffer for outages. */
    ASYNC_CONFIRMS(
        mapOf(
            "WEBHOOK_AMQP_PUBLISH_MODE" to "async",
            "WEBHOOK_AMQP_ENABLE_PUBLISHER_CONFIRM" to "true",
            "WEBHOOK_AMQP_MESSAGE_ID" to "true",
            "WEBHOOK_AMQP_BUFFER_CAPACITY" to "10000",
        ),
        atLeastOnce = true, exactlyOnceWhenHealthy = false,
    ),
}

/**
 * The same production scenarios for every [Variant] on every [Topology], on the Keycloak version
 * we run ([ItSettings.keycloakVersion]). Each subclass is one combination and starts its own
 * Keycloak and RabbitMQ, so they never share state and can be run on their own.
 *
 * Scenarios run in order; the last one stops Keycloak to check the shutdown path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class AmqpScenarios(private val variant: Variant, private val topology: Topology = Topology.SINGLE) {

    protected lateinit var stack: Stack
    private lateinit var api: KeycloakApi
    private lateinit var realmId: String

    private val realm = "webhook-it"
    private val users = 200
    protected val name: String get() = javaClass.simpleName

    @BeforeAll
    fun start() {
        stack = Stack(ItSettings.keycloakVersion, variant.settings, topology).start()
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
        assertEquals(1, stack.awaitPluginConnections(1), "plugin connections after 50 logins; ${stack.describeConnections()}")
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
            println("[$name] logins while the broker hangs: $report")
            assertEquals(0, report.failedRequests, "every login must still succeed")

            if (variant.atLeastOnce) {
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
            println("[$name] sent: $report")
            assertEquals(0, report.failedRequests, "Keycloak itself should have answered every request")

            val complete = observer.awaitDistinct(report.expectedEvents, 600_000)
            val arrived = observer.distinctByType()
            println("[$name] arrived: ${observer.distinctEvents} distinct events, ${observer.duplicates} duplicates; by type: $arrived")
            assertTrue(complete, "only ${observer.distinctEvents} of ${report.expectedEvents} events arrived")
            report.expectedByType.forEach { (type, count) ->
                assertEquals(count, arrived[type] ?: 0, "distinct $type events")
            }
            if (variant.exactlyOnceWhenHealthy) assertEquals(0, observer.duplicates, "duplicates on a healthy broker")
            assertTrue(observer.awaitCondition(30_000) { observer.backlog() == 0L }, "the broker should be fully drained")
        }
    }

    /**
     * The whole broker hanging for minutes under steady traffic: the case that decides between the
     * setups in production.
     */
    @Test
    @Order(5)
    fun `a long broker outage under steady traffic`() = underSteadyTraffic("long outage, ${ItSettings.outageSeconds}s") {
        stack.pauseBroker()
        try {
            Thread.sleep(ItSettings.outageSeconds * 1_000)
        } finally {
            stack.unpauseBroker()
        }
    }

    @Test
    @Order(100)
    fun `a graceful shutdown closes the transport and loses nothing`() {
        Observer(stack).use { observer ->
            val burst = Traffic(api, realm, users).run(2_000, ItSettings.concurrency)
            val logs = stack.stopKeycloakGracefully()

            assertTrue("Closed [webhook-amqp] transport" in logs, "Keycloak never closed the plugin's transport")
            assertTrue(observer.awaitDistinct(burst.expectedEvents, 60_000),
                "only ${observer.distinctEvents} of ${burst.expectedEvents} events arrived")
            if (variant == Variant.ASYNC_CONFIRMS) {
                // The summary counts drops over the whole run; what matters here is that the drain left nothing behind.
                assertNotNull(logs.lines().lastOrNull { "AMQP publisher stopped:" in it }, "the async publisher never logged its shutdown")
                val leftBehind = logs.lines().firstOrNull { "undelivered messages" in it }
                assertTrue(leftBehind == null, "shutdown left messages behind: $leftBehind")
            }
        }
    }

    /**
     * Runs [disruption] while clients send a fixed [ItSettings.outageRate] requests a second (the same
     * load for every setup), then prints latency and failures before, during and after it, and what
     * arrived. Every setup must recover on its own afterwards; one that promises at-least-once
     * must also keep requests fast and deliver everything.
     */
    protected fun underSteadyTraffic(label: String, disruption: () -> Unit) {
        Observer(stack).use { observer ->
            val run = Traffic(api, realm, users).around(disruption)
            val sent = run.report
            val from = run.fromMs
            val to = run.toMs
            run.print(name, "$label at ${ItSettings.outageRate} requests/s")

            // Whatever the setup promises, Keycloak and the plugin must come back on their own.
            assertTrue(api.passwordLogin(realm, "user199").ok, "Keycloak should answer again afterwards")
            val recovered = observer.awaitCondition(180_000) { observer.messages.any { it.username == "user199" } }
            observer.awaitCondition(60_000) { observer.distinctEvents >= sent.expectedEvents + 1 }
            println(
                "[$name]   arrived: ${observer.distinctEvents} of ${sent.expectedEvents + 1} events " +
                    "(${observer.duplicates} duplicates); recovered: $recovered"
            )
            assertTrue(recovered, "events should flow again afterwards")

            if (variant.atLeastOnce) {
                assertEquals(0, sent.failedRequests, "no request may fail during the disruption")
                val during = sent.samples.filter { it.startedAtMs in from until to }.map { it.latencyMs }.sorted()
                val p99 = if (during.isEmpty()) 0 else during[((during.size - 1) * 0.99).toInt()]
                assertTrue(p99 < 2_000, "p99 during the disruption was ${p99}ms")
                assertTrue(observer.distinctEvents >= sent.expectedEvents + 1, "every event should arrive")
            }
        }
    }
}

/**
 * Failures only a cluster can have, on top of every single-broker scenario. Quorum queues keep
 * accepting messages as long as a majority of nodes is up; the plugin has to find a live node.
 */
abstract class ClusterScenarios(variant: Variant) : AmqpScenarios(variant, Topology.CLUSTER) {

    @Test
    @Order(10)
    fun `the node the plugin is connected to crashes`() = underSteadyTraffic("node crash") {
        val node = stack.pluginNode()
        stack.kill(node)
        Thread.sleep(30_000)
        stack.restart(node)
    }

    @Test
    @Order(11)
    fun `a rolling restart of every node`() = underSteadyTraffic("rolling restart") {
        stack.rabbits.forEach { node ->
            stack.stop(node)
            stack.restart(node)
        }
    }

    @Test
    @Order(12)
    fun `the cluster loses its majority for a while`() = underSteadyTraffic("majority lost") {
        // With pause_minority the one node left stops serving too, so this is a full outage
        // that the cluster has to heal from by itself.
        val (first, second) = stack.rabbits.take(2)
        stack.stop(first)
        stack.stop(second)
        Thread.sleep(60_000)
        stack.restart(first, second)
    }
}

// ---- single broker ----
class SyncDefaultsTest : AmqpScenarios(Variant.SYNC)
class SyncWithConfirmsTest : AmqpScenarios(Variant.SYNC_CONFIRMS)
class AsyncWithoutConfirmsTest : AmqpScenarios(Variant.ASYNC)
class AsyncAtLeastOnceTest : AmqpScenarios(Variant.ASYNC_CONFIRMS)

// ---- three-node quorum cluster ----
class SyncDefaultsClusterTest : ClusterScenarios(Variant.SYNC)
class SyncWithConfirmsClusterTest : ClusterScenarios(Variant.SYNC_CONFIRMS)
class AsyncWithoutConfirmsClusterTest : ClusterScenarios(Variant.ASYNC)
class AsyncAtLeastOnceClusterTest : ClusterScenarios(Variant.ASYNC_CONFIRMS)
