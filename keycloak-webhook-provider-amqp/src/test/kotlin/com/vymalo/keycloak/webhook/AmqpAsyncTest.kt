package com.vymalo.keycloak.webhook

import com.rabbitmq.client.GetResponse
import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.eventually
import com.vymalo.keycloak.webhook.testing.withConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WEBHOOK_AMQP_PUBLISH_MODE=async against a real broker: same messages as sync mode,
 * never blocking the caller, riding out an outage, giving up on refused messages, and
 * draining on shutdown. Each test publishes to its own exchange.
 */
class AmqpAsyncTest {

    companion object {
        private val broker = TestBroker()

        @JvmStatic
        @BeforeAll
        fun startBroker() = broker.start()

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()
    }

    private val exchange = "async-${UUID.randomUUID()}"

    /** Async mode with confirms on and a short confirm timeout, so the watchdog acts within the test. */
    private fun <T> withAsync(vararg extra: Pair<String, String?>, block: () -> T): T = withConfig(
        amqpHostKey to broker.host,
        amqpPortKey to broker.amqpPort.toString(),
        amqpUsernameKey to TestBroker.USER,
        amqpPasswordKey to TestBroker.PASSWORD,
        amqpExchangeKey to exchange,
        amqpPublishModeKey to "async",
        amqpEnablePublisherConfirm to "true",
        amqpPublisherConfirmTimeout to "1000",
        *extra,
        block = block,
    )

    /** Runs [block] with a fresh factory and always shuts it down, as Keycloak does on stop. */
    private fun <T> withFactory(block: (AmqpWebhookFactory) -> T): T {
        val factory = AmqpWebhookFactory()
        try {
            return block(factory)
        } finally {
            factory.close()
        }
    }

    private fun AmqpWebhookFactory.login(id: String) =
        inSession { it.onEvent(Fixtures.loginEvent().apply { this.id = id }) }

    /** Everything currently in [queue], read over one connection. */
    private fun drain(queue: String): List<GetResponse> =
        broker.admin { channel -> generateSequence { channel.basicGet(queue, true) }.toList() }

    private fun GetResponse.eventId(): String =
        Regex(""""id":"([^"]+)"""").find(String(body, Charsets.UTF_8))!!.groupValues[1]

    @Test
    fun `sends the same messages as sync mode, in order`() {
        val queue = broker.bindQueue(exchange)
        withAsync {
            withFactory { factory ->
                factory.inSession {
                    it.onEvent(Fixtures.loginEvent())
                    it.onEvent(Fixtures.adminEvent(), true)
                    it.onEvent(Fixtures.anonymousEvent())
                }
            }
        }

        val messages = List(3) { broker.nextMessage(queue) }
        assertEquals(
            listOf(Fixtures.PayloadJson.LOGIN, Fixtures.PayloadJson.ADMIN, Fixtures.PayloadJson.ANONYMOUS),
            messages.map { String(it.body, Charsets.UTF_8) },
        )
        assertEquals(
            listOf(
                "KC_CLIENT.realm-id.account.user-1.LOGIN",
                "KC_CLIENT.realm-id.admin-cli.admin-1.USER-CREATE",
                "KC_CLIENT.realm-id.xxx.xxx.LOGIN_ERROR",
            ),
            messages.map { it.envelope.routingKey },
        )
        val props = messages.first().props
        assertEquals("application/json", props.contentType)
        assertEquals("com.vymalo.keycloak.webhook.WebhookPayload", props.headers["__TypeId__"].toString())
    }

    @Test
    fun `publishing never waits for an unreachable broker`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        withAsync(amqpPortKey to deadPort.toString()) {
            withFactory { factory ->
                val started = System.nanoTime()
                repeat(200) { factory.login("evt-$it") }
                val tookMs = (System.nanoTime() - started) / 1_000_000
                // Sync mode would spend about two seconds per event here, retrying the connection.
                assertTrue(tookMs < 1_000, "200 events took ${tookMs}ms")
            }
        }
    }

    @Test
    fun `events sent while the broker hangs arrive after it recovers`() {
        val queue = broker.bindQueue(exchange)
        withAsync {
            withFactory { factory ->
                factory.login("before")
                eventually { broker.admin { it.messageCount(queue) }.takeIf { it == 1L } }

                // A paused container is the nasty kind of outage: connections hang instead of failing.
                broker.dockerClient.pauseContainerCmd(broker.containerId).exec()
                try {
                    repeat(5) { factory.login("during-$it") }
                    Thread.sleep(3_000) // long enough for the confirm watchdog to give up on the connection
                } finally {
                    broker.dockerClient.unpauseContainerCmd(broker.containerId).exec()
                }

                val expected = setOf("before") + List(5) { "during-$it" }
                val seen = mutableSetOf<String>()
                eventually(timeoutMs = 60_000) {
                    drain(queue).forEach { seen += it.eventId() }
                    seen.takeIf { it.containsAll(expected) }
                }
            }
        }
    }

    @Test
    fun `a message the broker keeps refusing is dropped and doesn't block the ones after it`() {
        // A queue that is full and refuses new messages: with confirms on, the broker nacks them.
        val full = "full-${UUID.randomUUID()}"
        broker.admin {
            it.exchangeDeclare(exchange, "topic", true)
            it.queueDeclare(full, false, false, false, mapOf("x-max-length" to 1, "x-overflow" to "reject-publish"))
            it.queueBind(full, exchange, "#")
            it.basicPublish(exchange, "filler", null, "filler".toByteArray())
        }

        withAsync {
            withFactory { factory ->
                factory.login("refused")
                Thread.sleep(2_000) // the retries happen right away; this is plenty for all of them
                broker.admin { it.queuePurge(full) }

                factory.login("after")
                val next = broker.nextMessage(full)
                assertEquals("after", next.eventId())
            }
        }
        assertEquals(emptyList(), drain(full).map { it.eventId() })
    }

    @Test
    fun `closing delivers what is still buffered, over a single connection`() {
        val queue = broker.bindQueue(exchange)
        withAsync {
            withFactory { factory ->
                repeat(200) { factory.login("evt-$it") }
                eventually { broker.ctl("list_connections", "name").takeIf { it.size == 1 } }
            }
            // withFactory closed the factory right after the last event; the drain must have sent everything.
            eventually { broker.ctl("list_connections", "name").takeIf { it.isEmpty() } }
        }

        val ids = drain(queue).map { it.eventId() }
        assertEquals(List(200) { "evt-$it" }, ids)
    }
}
