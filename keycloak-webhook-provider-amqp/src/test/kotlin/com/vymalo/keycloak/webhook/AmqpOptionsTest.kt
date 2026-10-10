package com.vymalo.keycloak.webhook

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The opt-in AMQP settings, each checked against a real broker. */
class AmqpOptionsTest {

    companion object {
        private const val EXCHANGE = "keycloak-options"
        private val broker = TestBroker()

        @JvmStatic
        @BeforeAll
        fun startBroker() = broker.start()

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()
    }

    private val credentials = arrayOf(
        amqpUsernameKey to TestBroker.USER,
        amqpPasswordKey to TestBroker.PASSWORD,
        amqpExchangeKey to EXCHANGE,
    )

    private fun <T> withBroker(vararg extra: Pair<String, String?>, block: () -> T): T = withConfig(
        amqpHostKey to broker.host,
        amqpPortKey to broker.amqpPort.toString(),
        *credentials,
        *extra,
        block = block,
    )

    /** Runs one session with one login event on a fresh factory, then shuts the factory down. */
    private fun publishLogin() {
        val factory = AmqpWebhookFactory()
        try {
            factory.inSession { it.onEvent(Fixtures.loginEvent()) }
        } finally {
            factory.close()
        }
    }

    /**
     * The client shuffles the addresses on every connect, so one publish only hits the dead
     * broker first half the time. Four fresh connections make skipping failover entirely a 1-in-16 event.
     */
    @Test
    fun `addresses fail over past a broker that is down`() {
        val queue = broker.bindQueue(EXCHANGE)
        val deadPort = ServerSocket(0).use { it.localPort }

        withConfig(
            amqpHostKey to null,
            amqpPortKey to null,
            amqpAddressesKey to "${broker.host}:$deadPort,${broker.host}:${broker.amqpPort}",
            *credentials,
        ) { repeat(4) { publishLogin() } }

        repeat(4) { assertEquals(Fixtures.PayloadJson.LOGIN, String(broker.nextMessage(queue).body, Charsets.UTF_8)) }
    }

    @Test
    fun `heartbeat is negotiated as configured`() {
        withBroker(amqpHeartbeatSecondsKey to "7") {
            val factory = AmqpWebhookFactory()
            try {
                factory.inSession { it.onEvent(Fixtures.loginEvent()) }
                // `timeout` is the heartbeat the broker agreed on for each client connection.
                eventually { broker.ctl("list_connections", "timeout").takeIf { it == listOf("7") } }
            } finally {
                factory.close()
            }
        }
    }

    @Test
    fun `persistent messages with ids, one id per message`() {
        val queue = broker.bindQueue(EXCHANGE)
        withBroker(amqpPersistentKey to "true", amqpMessageIdKey to "true") {
            val factory = AmqpWebhookFactory()
            try {
                factory.inSession {
                    it.onEvent(Fixtures.loginEvent())
                    it.onEvent(Fixtures.loginEvent())
                }
            } finally {
                factory.close()
            }
        }

        val first = broker.nextMessage(queue).props
        val second = broker.nextMessage(queue).props
        assertEquals(2, first.deliveryMode)
        assertNotNull(UUID.fromString(first.messageId))
        assertNotEquals(first.messageId, second.messageId)
    }

    @Test
    fun `the exchange is declared as a durable topic exchange when asked to`() {
        val exchange = "declared-${UUID.randomUUID()}"
        withBroker(amqpExchangeKey to exchange, amqpDeclareExchangeKey to "true") { publishLogin() }

        assertEquals(
            listOf("$exchange\ttopic\ttrue"),
            broker.ctl("list_exchanges", "name", "type", "durable").filter { it.startsWith(exchange) },
        )
    }

    @Test
    fun `without the option a missing exchange is left missing`() {
        val exchange = "undeclared-${UUID.randomUUID()}"
        withBroker(amqpExchangeKey to exchange) { publishLogin() }

        assertNull(broker.ctl("list_exchanges", "name").firstOrNull { it == exchange })
    }
}
