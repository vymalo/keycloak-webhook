package com.vymalo.keycloak.webhook.it

import com.rabbitmq.client.Address
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.ServerSocket
import java.time.Duration

/** How RabbitMQ runs next to Keycloak. */
enum class Topology {
    /** No RabbitMQ at all: for scenarios about the HTTP and Syslog providers. */
    NONE,

    /** One broker, as in the simplest deployments and in the README's examples. */
    SINGLE,

    /**
     * Three nodes set up for production: one cluster (classic config peer discovery),
     * `pause_minority` so a node cut off from the others stops rather than diverge, quorum
     * queues on the consumer side (every message replicated, confirmed once a majority has it),
     * and the plugin connected to all three through WEBHOOK_AMQP_ADDRESSES with persistent messages.
     */
    CLUSTER,
}

/**
 * One Keycloak with the plugin installed, next to what it sends to, on a private Docker network,
 * set up the way production runs them: all four shaded jars in /opt/keycloak/providers and the
 * plugin configured through WEBHOOK_* environment variables.
 *
 * The receivers are RabbitMQ (none, one node or a cluster, see [topology]) and any [sidecars]:
 * other containers a scenario needs on the same network, such as an OpenAPI mock or a syslog
 * server. Receivers that live in the test JVM reach Keycloak through Testcontainers' host access.
 */
class Stack(
    val keycloakVersion: String,
    pluginSettings: Map<String, String>,
    val topology: Topology = Topology.SINGLE,
    sidecars: (Network) -> List<Container> = { emptyList() },
) : AutoCloseable {

    open class Container(image: String) : GenericContainer<Container>(DockerImageName.parse(image))

    companion object {
        const val EXCHANGE = "keycloak"
        const val BROKER_USER = "keycloak"
        const val BROKER_PASSWORD = "secret"
        private const val RABBIT_IMAGE = "rabbitmq:3.13-alpine"
        private const val AMQP_PORT = 5672

        /** The plugin's AMQP settings every scenario shares: only the broker and exchange, nothing opt-in. */
        val baseSettings = mapOf(
            "WEBHOOK_AMQP_HOST" to "rabbitmq",
            "WEBHOOK_AMQP_PORT" to "$AMQP_PORT",
            "WEBHOOK_AMQP_USERNAME" to BROKER_USER,
            "WEBHOOK_AMQP_PASSWORD" to BROKER_PASSWORD,
            "WEBHOOK_AMQP_VHOST" to "/",
            "WEBHOOK_AMQP_EXCHANGE" to EXCHANGE,
        )

        private val clusterNodes = listOf("rabbit-1", "rabbit-2", "rabbit-3")

        /** What the plugin gets on top of the variant's settings when it talks to the cluster. */
        private val clusterSettings = mapOf(
            "WEBHOOK_AMQP_ADDRESSES" to clusterNodes.joinToString(",") { "$it:$AMQP_PORT" },
            "WEBHOOK_AMQP_PERSISTENT" to "true",
        )

        private val clusterConfig = """
            cluster_formation.peer_discovery_backend = classic_config
            ${clusterNodes.mapIndexed { i, node -> "cluster_formation.classic_config.nodes.${i + 1} = rabbit@$node" }.joinToString("\n")}
            cluster_partition_handling = pause_minority
        """.trimIndent().lines().joinToString("\n") { it.trim() }
    }

    private val network: Network = Network.newNetwork()

    /** Each node's name on the network, in the same order as [rabbits]. */
    private val nodeNames = when (topology) {
        Topology.NONE -> emptyList()
        Topology.SINGLE -> listOf("rabbitmq")
        Topology.CLUSTER -> clusterNodes
    }

    /**
     * One fixed host port per node, kept across restarts. With Docker's usual random ports a node
     * that restarts comes back somewhere else, and after a rolling restart no client of the test
     * would find the cluster any more.
     */
    private val hostPorts = nodeNames.map { ServerSocket(0).use { socket -> socket.localPort } }

    /** The broker nodes: none, one for [Topology.SINGLE], three for [Topology.CLUSTER]. */
    val rabbits: List<Container> = when (topology) {
        Topology.NONE -> emptyList()
        Topology.SINGLE -> listOf(rabbitNode("rabbitmq", hostPorts[0]))
        Topology.CLUSTER -> clusterNodes.mapIndexed { i, name ->
            rabbitNode(name, hostPorts[i])
                // The node name is rabbit@<hostname>, and the cluster finds its members by those names.
                .withCreateContainerCmdModifier { it.withHostName(name) }
                .withEnv("RABBITMQ_ERLANG_COOKIE", "keycloak-webhook-integration-tests")
                .withCopyToContainer(Transferable.of(clusterConfig), "/etc/rabbitmq/conf.d/20-cluster.conf")
        }
    }

    private fun rabbitNode(alias: String, hostPort: Int) = Container(RABBIT_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withEnv("RABBITMQ_DEFAULT_USER", BROKER_USER)
        .withEnv("RABBITMQ_DEFAULT_PASS", BROKER_PASSWORD)
        .withExposedPorts(AMQP_PORT)
        .apply { portBindings = listOf("$hostPort:$AMQP_PORT") }
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1).withStartupTimeout(Duration.ofMinutes(3)))

    private val sidecarContainers: List<Container> = sidecars(network)

    val keycloak: Container = Container("quay.io/keycloak/keycloak:$keycloakVersion")
        .withNetwork(network)
        .withExposedPorts(8080)
        // Keycloak 26 reads the bootstrap names, older versions the KEYCLOAK_ADMIN ones; both are harmless to set.
        .withEnv("KEYCLOAK_ADMIN", "admin")
        .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        // By default Keycloak sizes its heap as a share of the memory Docker offers, which on a desktop can
        // be gigabytes; a fixed 1 GB is plenty for these tests and keeps a whole run laptop-friendly.
        // Versions without JAVA_OPTS_KC_HEAP (before 24) just ignore it.
        .withEnv("JAVA_OPTS_KC_HEAP", "-Xms256m -Xmx1g")
        .withEnv(baseSettings + (if (topology == Topology.CLUSTER) clusterSettings else emptyMap()) + pluginSettings)
        .withCommand("start-dev")
        .apply {
            ItSettings.pluginJars.forEach {
                withCopyFileToContainer(MountableFile.forHostPath(it.toPath()), "/opt/keycloak/providers/${it.name}")
            }
        }
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(5)))

    /**
     * Starts the broker (a cluster's nodes together, so they find each other) and creates the
     * exchange, then the sidecars, then Keycloak, so everything it sends to is up first.
     */
    fun start(): Stack {
        if (rabbits.isNotEmpty()) {
            Startables.deepStart(rabbits).join()
            awaitClusterHealthy()
            brokerChannel { it.exchangeDeclare(EXCHANGE, "topic", true) }
        }
        Startables.deepStart(sidecarContainers).join()
        keycloak.start()
        return this
    }

    val keycloakUrl: String get() = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}"

    // ---- talking to the broker from the test ----

    /** Where the test reaches every node from the host; the ports stay the same across restarts. */
    fun brokerAddresses(): List<Address> = rabbits.mapIndexed { i, node -> Address(node.host, hostPorts[i]) }

    /** For the test's own connections. On a cluster they recover by themselves when their node goes away. */
    fun brokerConnectionFactory() = ConnectionFactory().apply {
        username = BROKER_USER
        password = BROKER_PASSWORD
        isAutomaticRecoveryEnabled = true
        networkRecoveryInterval = 1_000
    }

    fun <T> brokerChannel(block: (Channel) -> T): T =
        brokerConnectionFactory().newConnection(brokerAddresses(), "it-admin").use { c -> c.createChannel().use(block) }

    private fun Container.isRunning() =
        dockerClient.inspectContainerCmd(containerId).exec().state.running == true

    private fun anyRunningNode() = rabbits.first { it.isRunning() }

    fun rabbitctl(vararg args: String): List<String> =
        anyRunningNode().execInContainer("rabbitmqctl", *args, "--silent").stdout.lines().filter { it.isNotBlank() }

    /**
     * Every client connection as (name, peer host, node), asked from each running node and merged
     * by name, so the answer covers the whole cluster whichever node lists what.
     */
    private fun connections(): List<List<String>> = rabbits.filter { it.isRunning() }.flatMap { node ->
        // `host` is the address the client connected to, i.e. which node took the connection;
        // rabbitmqctl in 3.13 has no column that names the node itself.
        val result = node.execInContainer("rabbitmqctl", "list_connections", "name", "peer_host", "host", "--silent")
        check(result.exitCode == 0) { "rabbitmqctl list_connections failed: ${result.stderr}" }
        result.stdout.lines().filter { it.isNotBlank() }.map { line -> line.split('\t').map(String::trim) }
    }.distinctBy { it.first() }

    /** For failure messages: every connection the broker has, and the address Keycloak's connections come from. */
    fun describeConnections(): String = "keycloak at ${keycloakIp()}, broker connections (name, peer, node): ${connections()}"

    /** Broker connections opened from Keycloak's container, i.e. the plugin's; the test's own come from the host. */
    fun pluginConnections(): Int = connections().count { it.getOrNull(1) == keycloakIp() }

    /**
     * The plugin's connection count once it settles on [expected], or whatever it is after [timeoutMs].
     * rabbitmqctl can list a connection a moment after it's opened, so a single look can come up short.
     */
    fun awaitPluginConnections(expected: Int, timeoutMs: Long = 15_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val count = pluginConnections()
            if (count == expected || System.currentTimeMillis() > deadline) return count
            Thread.sleep(500)
        }
    }

    /** The node the plugin is connected to, waiting a little in case it is between connections. */
    fun pluginNode(timeoutMs: Long = 30_000): Container {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val table = connections()
            val nodeIp = table.firstOrNull { it.getOrNull(1) == keycloakIp() }?.getOrNull(2)
            if (nodeIp != null) {
                return rabbits.firstOrNull { ipOf(it) == nodeIp } ?: error("plugin connected to an unknown address $nodeIp")
            }
            check(System.currentTimeMillis() < deadline) {
                "the plugin (${keycloakIp()}) isn't connected to any node; connections: $table"
            }
            Thread.sleep(1_000)
        }
    }

    /** Asked from Docker every time: a restarted container can come back with a different address. */
    /**
     * A container's address on the stack's own network. Containers can sit on more than one (Testcontainers'
     * host access adds the default bridge), and only this one is where the plugin's traffic flows.
     */
    private fun ipOf(node: Container): String? = node.dockerClient.inspectContainerCmd(node.containerId).exec()
        .networkSettings.networks.values.firstOrNull { it.networkID == network.id }?.ipAddress

    private fun keycloakIp(): String = checkNotNull(ipOf(keycloak)) { "Keycloak isn't on the stack's network" }

    // ---- disturbing the broker ----

    /** Freezes every node: connections stay open but nothing answers, the nastiest kind of outage. */
    fun pauseBroker() = rabbits.forEach { it.dockerClient.pauseContainerCmd(it.containerId).exec() }
    fun unpauseBroker() = rabbits.forEach { it.dockerClient.unpauseContainerCmd(it.containerId).exec() }

    /** A node crashing: no goodbye to anyone. */
    fun kill(node: Container) = node.dockerClient.killContainerCmd(node.containerId).exec()

    /** A node shut down on purpose, as in a rolling restart or maintenance. */
    fun stop(node: Container) = node.dockerClient.stopContainerCmd(node.containerId).withTimeout(30).exec()

    /**
     * Starts stopped or killed nodes again and waits until the cluster is whole. They rejoin with
     * their data. Nodes that must come back together (a lost majority) go in one call.
     */
    fun restart(vararg nodes: Container) {
        nodes.forEach { it.dockerClient.startContainerCmd(it.containerId).exec() }
        awaitClusterHealthy()
    }

    /** Waits until every node is up and, on a cluster, all of them run as one cluster again. */
    fun awaitClusterHealthy(timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val healthy = runCatching {
                rabbits.all { it.isRunning() } &&
                    (topology != Topology.CLUSTER ||
                        rabbits.all { node ->
                            val status = node.execInContainer("rabbitmqctl", "cluster_status", "--formatter", "json").stdout
                            clusterNodes.all { "rabbit@$it" in status.substringAfter("\"running_nodes\"").substringBefore("]") }
                        })
            }.getOrDefault(false)
            if (healthy) return
            Thread.sleep(1_000)
        }
        error("RabbitMQ didn't come back as one healthy ${topology.name.lowercase()} within ${timeoutMs / 1000}s")
    }

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
        sidecarContainers.forEach { runCatching { it.stop() } }
        rabbits.forEach { runCatching { it.stop() } }
        runCatching { network.close() }
    }
}
