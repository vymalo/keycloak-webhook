package com.vymalo.keycloak.webhook

import com.rabbitmq.client.GetResponse
import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.models.AmqpConfig
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.eventually
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The async publisher's odd corners, checked through its [AsyncAmqpTransport.Stats] as well as
 * through what arrives: a test that only looks at the queue can pass without the code path
 * it's named after ever running.
 */
class AsyncEdgeCaseTest {

    companion object {
        private val broker = TestBroker()

        @JvmStatic
        @BeforeAll
        fun startBroker() = broker.start()

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()
    }

    private val exchange = "edge-${UUID.randomUUID()}"

    private fun transport(vararg overrides: Pair<String, String?>): AsyncAmqpTransport {
        val settings = mapOf(
            amqpHostKey to broker.host,
            amqpPortKey to broker.amqpPort.toString(),
            amqpUsernameKey to TestBroker.USER,
            amqpPasswordKey to TestBroker.PASSWORD,
            amqpExchangeKey to exchange,
            amqpPublishModeKey to "async",
            amqpEnablePublisherConfirm to "true",
            amqpPublisherConfirmTimeout to "1000",
        ) + overrides
        return AsyncAmqpTransport(AmqpConfig.from(ConfigSource { settings[it] }))
    }

    private fun event(id: String) = Fixtures.loginEvent().apply { this.id = id }.toPayload()

    /** Everything currently in [queue], read over one connection. */
    private fun drain(queue: String): List<GetResponse> =
        broker.admin { channel -> generateSequence { channel.basicGet(queue, true) }.toList() }

    private fun GetResponse.eventId(): String =
        Regex(""""id":"([^"]+)"""").find(String(body, Charsets.UTF_8))!!.groupValues[1]

    /** Pauses the broker container for the duration of [block]: connections hang rather than fail. */
    private fun <T> brokerHangs(block: () -> T): T {
        broker.dockerClient.pauseContainerCmd(broker.containerId).exec()
        try {
            return block()
        } finally {
            broker.dockerClient.unpauseContainerCmd(broker.containerId).exec()
        }
    }

    @Test
    fun `a hanging broker trips the confirm watchdog, and the stuck messages are sent again`() {
        val queue = broker.bindQueue(exchange)
        transport().use { transport ->
            transport.publish(event("before"))
            eventually { broker.admin { it.messageCount(queue) }.takeIf { it == 1L } }

            brokerHangs {
                repeat(5) { transport.publish(event("during-$it")) }
                eventually { transport.stats.watchdogRecycles.get().takeIf { it >= 1 } }
            }

            val expected = setOf("before") + List(5) { "during-$it" }
            val seen = mutableSetOf<String>()
            eventually(timeoutMs = 60_000) {
                drain(queue).forEach { seen += it.eventId() }
                seen.takeIf { it.containsAll(expected) }
            }
            assertTrue(transport.stats.resent.get() >= 1, "stuck messages should have been resent")
        }
    }

    @Test
    fun `when the buffer overflows during an outage, the newest events survive`() {
        val queue = broker.bindQueue(exchange)
        transport(amqpBufferCapacityKey to "3", amqpInflightCapacityKey to "1").use { transport ->
            transport.publish(event("warm-up"))
            eventually { broker.admin { it.messageCount(queue) }.takeIf { it == 1L } }
            drain(queue)

            brokerHangs {
                repeat(10) { transport.publish(event("m$it")) }
                eventually { transport.stats.watchdogRecycles.get().takeIf { it >= 1 } }
            }

            val newest = setOf("m7", "m8", "m9")
            val seen = mutableSetOf<String>()
            eventually(timeoutMs = 60_000) {
                drain(queue).forEach { seen += it.eventId() }
                seen.takeIf { it.containsAll(newest) }
            }
            // m0 was already on the wire when the broker hung, so it may still arrive over the old connection.
            assertTrue(seen - newest - "m0" == emptySet<String>(), "only the newest should survive, got $seen")
            assertTrue(transport.stats.overflowDropped.get() >= 6, "dropped ${transport.stats.overflowDropped}")
        }
    }

    @Test
    fun `a resent message keeps its message id, so consumers can drop the copy`() {
        val queue = broker.bindQueue(exchange)
        transport(amqpMessageIdKey to "true").use { transport ->
            transport.publish(event("warm-up"))
            eventually { broker.admin { it.messageCount(queue) }.takeIf { it == 1L } }

            brokerHangs {
                repeat(5) { transport.publish(event("during-$it")) }
                eventually { transport.stats.watchdogRecycles.get().takeIf { it >= 1 } }
            }

            val received = mutableListOf<GetResponse>()
            eventually(timeoutMs = 60_000) {
                received += drain(queue)
                received.map { it.eventId() }.toSet().takeIf { it.size == 6 }
            }
            val idsPerEvent = received.groupBy({ it.eventId() }, { it.props.messageId })
            idsPerEvent.forEach { (event, ids) -> assertEquals(1, ids.toSet().size, "$event arrived with ids $ids") }
            assertEquals(6, idsPerEvent.values.map { it.first() }.toSet().size, "every event has its own id")
        }
    }

    @Test
    fun `a message the broker rejects by closing the channel is given up on, and the rest carries on`() {
        // The exchange doesn't exist yet: publishing to it makes the broker close the channel (404).
        transport().use { transport ->
            transport.publish(event("to-nowhere"))
            eventually(timeoutMs = 30_000) { transport.stats.refusedDropped.get().takeIf { it == 1L } }

            val queue = broker.bindQueue(exchange)
            transport.publish(event("after"))
            assertEquals("after", broker.nextMessage(queue).eventId())
            assertEquals(emptyList(), drain(queue).map { it.eventId() })
        }
    }

    @Test
    fun `many threads publishing at once lose nothing and keep each thread's order`() {
        val queue = broker.bindQueue(exchange)
        val threads = 8
        val perThread = 250
        transport(amqpBufferCapacityKey to "5000").use { transport ->
            val start = CountDownLatch(1)
            val workers = List(threads) { t ->
                thread { start.await(); repeat(perThread) { transport.publish(event("t$t-$it")) } }
            }
            start.countDown()
            workers.forEach { it.join() }

            val ids = mutableListOf<String>()
            eventually(timeoutMs = 30_000) {
                ids += drain(queue).map { it.eventId() }
                ids.takeIf { it.size >= threads * perThread }
            }
            assertEquals(threads * perThread, ids.size, "no duplicates on a healthy broker")
            repeat(threads) { t ->
                assertEquals(List(perThread) { "t$t-$it" }, ids.filter { it.startsWith("t$t-") }, "order of thread $t")
            }
        }
    }

    @Test
    fun `the broker dropping every connection mid-stream loses nothing`() {
        val queue = broker.bindQueue(exchange)
        transport(amqpBufferCapacityKey to "5000").use { transport ->
            repeat(250) { transport.publish(event("a$it")) }
            broker.execInContainer("rabbitmqctl", "close_all_connections", "test: mid-stream outage")
            repeat(250) { transport.publish(event("b$it")) }

            val expected = List(250) { "a$it" } + List(250) { "b$it" }
            val seen = mutableSetOf<String>()
            eventually(timeoutMs = 60_000) {
                drain(queue).forEach { seen += it.eventId() }
                seen.takeIf { it.containsAll(expected) }
            }
            assertTrue(transport.stats.connects.get() >= 2, "should have reconnected")
        }
    }

    @Test
    fun `without confirms, messages still go out in order`() {
        val queue = broker.bindQueue(exchange)
        transport(amqpEnablePublisherConfirm to null).use { transport ->
            repeat(100) { transport.publish(event("n$it")) }
            val ids = mutableListOf<String>()
            eventually { ids += drain(queue).map { it.eventId() }; ids.takeIf { it.size >= 100 } }
            assertEquals(List(100) { "n$it" }, ids)
        }
    }

    @Test
    fun `one message in flight at a time is slow but complete and in order`() {
        val queue = broker.bindQueue(exchange)
        transport(amqpInflightCapacityKey to "1").use { transport ->
            repeat(200) { transport.publish(event("s$it")) }
            val ids = mutableListOf<String>()
            eventually(timeoutMs = 30_000) { ids += drain(queue).map { it.eventId() }; ids.takeIf { it.size >= 200 } }
            assertEquals(List(200) { "s$it" }, ids)
        }
    }

    @Test
    fun `closing gives up in bounded time when the broker is unreachable`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val transport = transport(amqpPortKey to deadPort.toString())
        repeat(3) { transport.publish(event("lost-$it")) }

        val tookMs = timed { transport.close() }
        assertTrue(tookMs < 13_000, "close took ${tookMs}ms")
        assertPublisherStopped()
    }

    @Test
    fun `closing gives up in bounded time when the broker hangs`() {
        broker.bindQueue(exchange)
        val transport = transport()
        transport.publish(event("warm-up"))
        eventually { transport.stats.connects.get().takeIf { it == 1L } }

        brokerHangs {
            transport.publish(event("stuck"))
            val tookMs = timed { transport.close() }
            assertTrue(tookMs < 13_000, "close took ${tookMs}ms")
        }
        assertPublisherStopped()
    }

    @Test
    fun `publishing after close is a quiet no-op, and closing twice is harmless`() {
        val queue = broker.bindQueue(exchange)
        val transport = transport()
        transport.close()
        transport.publish(event("too-late"))
        transport.close()

        Thread.sleep(500)
        assertEquals(emptyList(), drain(queue).map { it.eventId() })
    }

    @Test
    fun `a message the broker nacks is retried, then given up on`() {
        // A full queue that refuses new messages: with confirms on, the broker answers with a nack.
        val full = "full-${UUID.randomUUID()}"
        broker.admin {
            it.exchangeDeclare(exchange, "topic", true)
            it.queueDeclare(full, false, false, false, mapOf("x-max-length" to 1, "x-overflow" to "reject-publish"))
            it.queueBind(full, exchange, "#")
            it.basicPublish(exchange, "filler", null, "filler".toByteArray())
        }
        transport().use { transport ->
            transport.publish(event("refused"))
            eventually { transport.stats.refusedDropped.get().takeIf { it == 1L } }
            assertEquals(0, transport.stats.watchdogRecycles.get(), "a nack is an answer, not a stuck confirm")
        }
    }

    @Test
    fun `unicode, emoji and a megabyte of representation arrive byte for byte`() {
        val queue = broker.bindQueue(exchange)
        val odd = Fixtures.adminEvent().apply {
            id = "odd"
            representation = """{"username":"zoë 🦀","note":"line\nbreak \"quoted\" <tag> & ${"x".repeat(1_000_000)}"}"""
        }.toPayload()
        transport().use { transport ->
            transport.publish(odd)
            val body = String(broker.nextMessage(queue).body, Charsets.UTF_8)
            assertEquals(com.google.gson.Gson().toJson(odd), body)
            assertTrue("zoë 🦀" in com.google.gson.Gson().fromJson(body, WebhookPayload::class.java).representation!!)
        }
    }

    @Test
    fun `closing an idle transport is quick`() {
        val transport = transport()
        eventually { transport.stats.connects.get().takeIf { it == 1L } }
        val tookMs = timed { transport.close() }
        assertTrue(tookMs < 1_000, "idle close took ${tookMs}ms")
        assertPublisherStopped()
    }

    private fun timed(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    /** No publisher thread may outlive its transport; test transports are closed before this runs. */
    private fun assertPublisherStopped() {
        eventually {
            Thread.getAllStackTraces().keys
                .none { it.name == "keycloak-webhook-amqp-publisher" && it.isAlive }
                .takeIf { it }
        }
    }
}
