package com.vymalo.keycloak.webhook

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.eventually
import com.vymalo.keycloak.webhook.testing.withConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the AMQP provider puts on the wire, checked against a real RabbitMQ.
 *
 * Every test binds a fresh queue to the exchange with `#`, drives the factory
 * like Keycloak would, and reads back exactly what arrived: routing key,
 * properties and body.
 */
class AmqpWebhookTest {

    private class Broker : GenericContainer<Broker>(DockerImageName.parse("rabbitmq:3.13-alpine"))

    companion object {
        private const val USER = "keycloak"
        private const val PASSWORD = "secret"
        private const val EXCHANGE = "keycloak-test"

        // The default guest user only works over loopback, and container traffic
        // never is, hence a dedicated user.
        private val broker = Broker()
            .withEnv("RABBITMQ_DEFAULT_USER", USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))

        @JvmStatic
        @BeforeAll
        fun startBroker() = broker.start()

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()
    }

    private lateinit var queue: String

    /** Opens a short-lived connection of our own, so killing the plugin's connections never takes the test's down. */
    private fun <T> admin(block: (Channel) -> T): T {
        val factory = ConnectionFactory().apply {
            host = broker.host
            port = broker.getMappedPort(5672)
            username = USER
            password = PASSWORD
        }
        return factory.newConnection().use { connection -> connection.createChannel().use(block) }
    }

    /** The plugin never declares the exchange, so the test does, and listens on everything published to it. */
    @BeforeEach
    fun bindQueue() {
        queue = "webhook-test-${UUID.randomUUID()}"
        admin {
            it.exchangeDeclare(EXCHANGE, "topic", true)
            it.queueDeclare(queue, false, false, false, null)
            it.queueBind(queue, EXCHANGE, "#")
        }
    }

    @AfterEach
    fun dropQueue() {
        admin { it.queueDelete(queue) }
    }

    private fun nextMessage(): GetResponse = eventually { admin { it.basicGet(queue, true) } }

    /** The minimal setup from the README; [extra] adds or overrides keys. */
    private fun <T> withBroker(vararg extra: Pair<String, String?>, block: () -> T): T = withConfig(
        amqpHostKey to broker.host,
        amqpPortKey to broker.getMappedPort(5672).toString(),
        amqpUsernameKey to USER,
        amqpPasswordKey to PASSWORD,
        amqpVHostKey to "/",
        amqpExchangeKey to EXCHANGE,
        *extra,
        block = block,
    )

    @Test
    fun `user event is published with the documented routing key, properties and body`() = withBroker {
        AmqpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) }

        val message = nextMessage()
        assertEquals(EXCHANGE, message.envelope.exchange)
        assertEquals("KC_CLIENT.realm-id.account.user-1.LOGIN", message.envelope.routingKey)
        assertEquals(Fixtures.PayloadJson.LOGIN, String(message.body, Charsets.UTF_8))

        val props = message.props
        assertEquals("Keycloak/Kotlin", props.appId)
        assertEquals("application/json", props.contentType)
        assertEquals("UTF-8", props.contentEncoding)
        assertEquals("com.vymalo.keycloak.webhook.WebhookPayload", props.headers["__TypeId__"].toString())
        // Transient and without a message id by default; making them opt-in keeps existing brokers' behaviour.
        assertNull(props.deliveryMode)
        assertNull(props.messageId)
    }

    @Test
    fun `admin event is routed by the admin's client and user`() = withBroker {
        AmqpWebhookFactory().inSession { it.onEvent(Fixtures.adminEvent(), true) }

        val message = nextMessage()
        assertEquals("KC_CLIENT.realm-id.admin-cli.admin-1.USER-CREATE", message.envelope.routingKey)
        assertEquals(Fixtures.PayloadJson.ADMIN, String(message.body, Charsets.UTF_8))
    }

    @Test
    fun `missing client and user become xxx in the routing key`() = withBroker {
        AmqpWebhookFactory().inSession { it.onEvent(Fixtures.anonymousEvent()) }

        val message = nextMessage()
        assertEquals("KC_CLIENT.realm-id.xxx.xxx.LOGIN_ERROR", message.envelope.routingKey)
        assertEquals(Fixtures.PayloadJson.ANONYMOUS, String(message.body, Charsets.UTF_8))
    }

    @Test
    fun `publisher confirms still deliver the message`() = withBroker(
        amqpEnablePublisherConfirm to "true",
        amqpPublisherConfirmTimeout to "2000",
    ) {
        AmqpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) }
        assertEquals(Fixtures.PayloadJson.LOGIN, String(nextMessage().body, Charsets.UTF_8))
    }

    /**
     * Keycloak serves logins on many threads at once, all sharing one channel. Starting
     * them together also covers the burst right after startup: threads that arrive while
     * the first connection is opened wait for it and publish, rather than dropping.
     */
    @Test
    fun `concurrent sessions all deliver, with publisher confirms`() {
        withBroker(amqpEnablePublisherConfirm to "true", amqpPublisherConfirmTimeout to "5000") {
            val factory = AmqpWebhookFactory()
            val start = CountDownLatch(1)
            val threads = List(8) {
                thread {
                    start.await()
                    repeat(25) { factory.inSession { it.onEvent(Fixtures.loginEvent()) } }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            factory.close()

            eventually { admin { it.queueDeclarePassive(queue).messageCount }.takeIf { it == 200 } }
        }
    }

    @Test
    fun `events from consecutive sessions arrive in order`() = withBroker {
        val factory = AmqpWebhookFactory()
        factory.inSession { it.onEvent(Fixtures.loginEvent()) }
        factory.inSession { it.onEvent(Fixtures.adminEvent(), true) }
        factory.inSession { it.onEvent(Fixtures.anonymousEvent()) }

        val bodies = List(3) { String(nextMessage().body, Charsets.UTF_8) }
        assertEquals(
            listOf(Fixtures.PayloadJson.LOGIN, Fixtures.PayloadJson.ADMIN, Fixtures.PayloadJson.ANONYMOUS),
            bodies,
        )
    }

    @Test
    fun `publishing recovers after the broker drops every connection`() = withBroker {
        val factory = AmqpWebhookFactory()
        factory.inSession { it.onEvent(Fixtures.loginEvent()) }
        nextMessage()

        broker.execInContainer("rabbitmqctl", "close_all_connections", "test: simulated outage")

        factory.inSession { it.onEvent(Fixtures.adminEvent(), true) }
        assertEquals(Fixtures.PayloadJson.ADMIN, String(nextMessage().body, Charsets.UTF_8))
    }

    /**
     * Regression guard for an upstream bug: the README calls the vhost optional, but
     * without it the client rejected the null vhost and `create()` threw straight into
     * Keycloak ("'virtualHost' must be non-null").
     */
    @Test
    fun `vhost is optional and defaults to the root vhost`() = withBroker(amqpVHostKey to null) {
        AmqpWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) }
        assertEquals(Fixtures.PayloadJson.LOGIN, String(nextMessage().body, Charsets.UTF_8))
    }
}
