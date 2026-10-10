package com.vymalo.keycloak.webhook

import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.eventually
import com.vymalo.keycloak.webhook.testing.withConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How long the plugin's broker connection lives. Uses its own broker, so connection
 * counts are not disturbed by factories other test classes leave open.
 */
class AmqpLifecycleTest {

    private class Broker : GenericContainer<Broker>(DockerImageName.parse("rabbitmq:3.13-alpine"))

    companion object {
        private const val USER = "keycloak"
        private const val PASSWORD = "secret"
        private const val EXCHANGE = "keycloak-lifecycle"
        private const val RECOVERY_INTERVAL_MS = 5_000L // amqp-client default

        private val broker = Broker()
            .withEnv("RABBITMQ_DEFAULT_USER", USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))

        @JvmStatic
        @BeforeAll
        fun startBroker() {
            broker.start()
            ConnectionFactory().apply {
                host = broker.host
                port = broker.getMappedPort(5672)
                username = USER
                password = PASSWORD
            }.newConnection().use { it.createChannel().use { ch -> ch.exchangeDeclare(EXCHANGE, "topic", true) } }
        }

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()
    }

    /** Client connections the broker currently has open. */
    private fun openConnections(): Int =
        broker.execInContainer("rabbitmqctl", "list_connections", "--silent", "name")
            .stdout.lines().count { it.isNotBlank() }

    private fun <T> withBroker(port: Int = broker.getMappedPort(5672), block: () -> T): T = withConfig(
        amqpHostKey to broker.host,
        amqpPortKey to port.toString(),
        amqpUsernameKey to USER,
        amqpPasswordKey to PASSWORD,
        amqpVHostKey to "/",
        amqpExchangeKey to EXCHANGE,
        block = block,
    )

    @Test
    fun `one connection serves every session and is closed with the factory`() {
        withBroker {
            val factory = AmqpWebhookFactory()
            repeat(5) { factory.inSession { it.onEvent(Fixtures.loginEvent()) } }

            eventually { openConnections().takeIf { it == 1 } }

            factory.close()
            eventually { openConnections().takeIf { it == 0 } }
        }
    }

    @Test
    fun `after the broker drops the connection the next event opens exactly one new one`() {
        withBroker {
            val factory = AmqpWebhookFactory()
            try {
                factory.inSession { it.onEvent(Fixtures.loginEvent()) }
                eventually { openConnections().takeIf { it == 1 } }

                broker.execInContainer("rabbitmqctl", "close_all_connections", "test: simulated outage")
                eventually { openConnections().takeIf { it == 0 } }

                factory.inSession { it.onEvent(Fixtures.loginEvent()) }
                eventually { openConnections().takeIf { it == 1 } }

                // The client's automatic recovery retries every 5s. The dropped connection
                // must be shut down properly, or it would come back as a second, leaked one.
                Thread.sleep(RECOVERY_INTERVAL_MS + 2_000)
                assertEquals(1, openConnections())
            } finally {
                factory.close()
            }
        }
    }

    @Test
    fun `an unreachable broker drops the event without failing the Keycloak session`() {
        // A port that was free a moment ago: connecting is refused straight away.
        val deadPort = ServerSocket(0).use { it.localPort }
        withBroker(port = deadPort) {
            val factory = AmqpWebhookFactory()
            try {
                factory.inSession { it.onEvent(Fixtures.loginEvent()) }
            } finally {
                factory.close()
            }
        }
        assertEquals(0, openConnections())
    }
}
