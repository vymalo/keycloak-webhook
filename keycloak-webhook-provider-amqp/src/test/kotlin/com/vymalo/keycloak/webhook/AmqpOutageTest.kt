package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.eventually
import com.vymalo.keycloak.webhook.testing.withConfig
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a broker that's down costs Keycloak's request threads. Needs no Docker: the
 * "broker" accepts each connection and hangs up at once, so every connection attempt
 * fails fast and can be counted.
 */
class AmqpOutageTest {

    /** Counts connection attempts and refuses each one by closing it. */
    private class HangingUpBroker : AutoCloseable {
        private val server = ServerSocket(0)
        val port = server.localPort
        val attempts = AtomicInteger()

        init {
            thread(isDaemon = true, name = "hanging-up-broker") {
                try {
                    while (true) server.accept().use { attempts.incrementAndGet() }
                } catch (_: SocketException) {
                    // closed by the test
                }
            }
        }

        override fun close() = server.close()
    }

    private val broker = HangingUpBroker()

    @AfterTest
    fun stopBroker() = broker.close()

    private fun <T> withDownBroker(block: (AmqpWebhookFactory) -> T): T = withConfig(
        amqpHostKey to "127.0.0.1",
        amqpPortKey to broker.port.toString(),
        amqpUsernameKey to "keycloak",
        amqpPasswordKey to "secret",
        amqpExchangeKey to "keycloak",
    ) {
        val factory = AmqpWebhookFactory()
        try {
            block(factory)
        } finally {
            factory.close()
        }
    }

    @Test
    fun `concurrent events share one reconnect round instead of each running their own`() {
        withDownBroker { factory ->
            val start = CountDownLatch(1)
            val logins = List(8) {
                thread {
                    start.await()
                    factory.inSession { it.onEvent(Fixtures.loginEvent()) }
                }
            }
            start.countDown()
            logins.forEach { it.join() }
        }
        // One round is three attempts. Before, every thread ran its own round, one after
        // another behind a single lock: 24 attempts, and the last login waited for all of them.
        assertEquals(3, broker.attempts.get())
    }

    @Test
    fun `after a failed round, events are dropped at once instead of trying again`() {
        withDownBroker { factory ->
            factory.inSession { it.onEvent(Fixtures.loginEvent()) }
            assertEquals(3, broker.attempts.get())

            val started = System.nanoTime()
            factory.inSession { it.onEvent(Fixtures.loginEvent()) }
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(3, broker.attempts.get())
            assertTrue(tookMs < 500, "a dropped event should cost nothing, took ${tookMs}ms")
        }
    }

    @Test
    fun `an interrupt during the pause between attempts stops the round and is kept`() {
        withDownBroker { factory ->
            var stillInterrupted = false
            val login = thread {
                factory.inSession { it.onEvent(Fixtures.loginEvent()) }
                stillInterrupted = Thread.currentThread().isInterrupted
            }
            // The first attempt failed, so the thread is in the pause before the second.
            eventually { broker.attempts.get().takeIf { it == 1 } }
            login.interrupt()
            login.join(2_000)

            assertTrue(!login.isAlive, "the interrupted login should have returned")
            assertTrue(stillInterrupted, "the interrupt must be passed on, not swallowed")
            assertEquals(1, broker.attempts.get())
        }
    }

    @Test
    fun `a session still running after shutdown does not reconnect`() {
        withDownBroker { factory ->
            // A redeploy closes the factory while a session that already has its listener runs on.
            factory.inSession {
                factory.close()
                it.onEvent(Fixtures.loginEvent())
            }
        }
        assertEquals(0, broker.attempts.get())
    }
}
