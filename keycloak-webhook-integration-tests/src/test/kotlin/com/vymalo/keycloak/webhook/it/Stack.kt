package com.vymalo.keycloak.webhook.it

import com.rabbitmq.client.ConnectionFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Duration

/**
 * One RabbitMQ and one Keycloak with the plugin installed, on a private Docker network,
 * set up the way production runs them: shaded jars in /opt/keycloak/providers and the
 * plugin configured through WEBHOOK_* environment variables.
 */
class Stack(val keycloakVersion: String, pluginSettings: Map<String, String>) : AutoCloseable {

    class Container(image: String) : GenericContainer<Container>(DockerImageName.parse(image))

    companion object {
        const val EXCHANGE = "keycloak"
        const val BROKER_USER = "keycloak"
        const val BROKER_PASSWORD = "secret"
        private const val BROKER_ALIAS = "rabbitmq"

        /** The plugin's AMQP settings every scenario shares: only the broker and exchange, nothing opt-in. */
        val baseSettings = mapOf(
            "WEBHOOK_AMQP_HOST" to BROKER_ALIAS,
            "WEBHOOK_AMQP_PORT" to "5672",
            "WEBHOOK_AMQP_USERNAME" to BROKER_USER,
            "WEBHOOK_AMQP_PASSWORD" to BROKER_PASSWORD,
            "WEBHOOK_AMQP_VHOST" to "/",
            "WEBHOOK_AMQP_EXCHANGE" to EXCHANGE,
        )
    }

    private val network: Network = Network.newNetwork()

    val rabbit: Container = Container("rabbitmq:3.13-alpine")
        .withNetwork(network)
        .withNetworkAliases(BROKER_ALIAS)
        .withEnv("RABBITMQ_DEFAULT_USER", BROKER_USER)
        .withEnv("RABBITMQ_DEFAULT_PASS", BROKER_PASSWORD)
        .withExposedPorts(5672)
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))

    val keycloak: Container = Container("quay.io/keycloak/keycloak:$keycloakVersion")
        .withNetwork(network)
        .withExposedPorts(8080)
        // Keycloak 26 reads the bootstrap names, older versions the KEYCLOAK_ADMIN ones; both are harmless to set.
        .withEnv("KEYCLOAK_ADMIN", "admin")
        .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        .withEnv(baseSettings + pluginSettings)
        .withCommand("start-dev")
        .apply {
            ItSettings.pluginJars.forEach {
                withCopyFileToContainer(MountableFile.forHostPath(it.toPath()), "/opt/keycloak/providers/${it.name}")
            }
        }
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(5)))

    /** Starts the broker, creates the exchange (the plugin expects it by default), then Keycloak. */
    fun start(): Stack {
        rabbit.start()
        brokerChannel { it.exchangeDeclare(EXCHANGE, "topic", true) }
        keycloak.start()
        return this
    }

    val keycloakUrl: String get() = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}"

    fun brokerConnectionFactory() = ConnectionFactory().apply {
        host = rabbit.host
        port = rabbit.getMappedPort(5672)
        username = BROKER_USER
        password = BROKER_PASSWORD
    }

    fun <T> brokerChannel(block: (com.rabbitmq.client.Channel) -> T): T =
        brokerConnectionFactory().newConnection("it-admin").use { c -> c.createChannel().use(block) }

    /** Broker connections opened from Keycloak's container, i.e. the plugin's; the test's own come from the host. */
    fun pluginConnections(): Int {
        val keycloakIp = keycloak.containerInfo.networkSettings.networks.values.map { it.ipAddress }.first { !it.isNullOrBlank() }
        return rabbit.execInContainer("rabbitmqctl", "list_connections", "peer_host", "--silent")
            .stdout.lines().count { it.trim() == keycloakIp }
    }

    fun pauseBroker() = rabbit.dockerClient.pauseContainerCmd(rabbit.containerId).exec()
    fun unpauseBroker() = rabbit.dockerClient.unpauseContainerCmd(rabbit.containerId).exec()

    /**
     * Stops Keycloak the way an orchestrator would (SIGTERM, then a grace period) and returns
     * everything it logged. The container is kept until [close], so its logs stay readable.
     */
    fun stopKeycloakGracefully(): String {
        keycloak.dockerClient.stopContainerCmd(keycloak.containerId).withTimeout(60).exec()
        return keycloak.logs
    }

    override fun close() {
        runCatching { keycloak.stop() }
        runCatching { rabbit.stop() }
        runCatching { network.close() }
    }
}
