package com.vymalo.keycloak.webhook

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import com.vymalo.keycloak.webhook.testing.eventually
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * A throwaway RabbitMQ for tests that need their own broker, plus the few things tests
 * do with it from the outside: plain admin connections, queues bound to an exchange,
 * reading back messages and asking `rabbitmqctl`.
 */
class TestBroker(setup: TestBroker.() -> Unit = {}) :
    GenericContainer<TestBroker>(DockerImageName.parse("rabbitmq:3.13-alpine")) {

    init {
        // The default guest user only works over loopback, and container traffic never is.
        withEnv("RABBITMQ_DEFAULT_USER", USER)
        withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
        withExposedPorts(AMQP_PORT)
        waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))
        // Not named `configure`: GenericContainer has a method of that name, and Kotlin would call it instead.
        setup()
    }

    val amqpPort: Int get() = getMappedPort(AMQP_PORT)

    /** A short-lived plain connection of the test's own, so tests never share state with the plugin's. */
    fun <T> admin(block: (Channel) -> T): T {
        val factory = ConnectionFactory().apply {
            host = this@TestBroker.host
            port = amqpPort
            username = USER
            password = PASSWORD
        }
        return factory.newConnection().use { connection -> connection.createChannel().use(block) }
    }

    /** Declares [exchange] if needed and binds a fresh queue to it that catches every routing key. */
    fun bindQueue(exchange: String): String = "test-${UUID.randomUUID()}".also { queue ->
        admin {
            it.exchangeDeclare(exchange, "topic", true)
            it.queueDeclare(queue, false, false, false, null)
            it.queueBind(queue, exchange, "#")
        }
    }

    fun nextMessage(queue: String): GetResponse = eventually { admin { it.basicGet(queue, true) } }

    /** One line per row of `rabbitmqctl <command> <columns>`, headers left out. */
    fun ctl(vararg command: String): List<String> =
        execInContainer("rabbitmqctl", *command, "--silent").stdout.lines().filter { it.isNotBlank() }

    companion object {
        const val USER = "keycloak"
        const val PASSWORD = "secret"
        const val AMQP_PORT = 5672
    }
}
