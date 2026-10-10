package com.vymalo.keycloak.webhook.it

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.testcontainers.Testcontainers
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * An HTTP receiver in the test JVM that records every request the plugin sends, and can be told to
 * misbehave. Keycloak reaches it through Testcontainers' host access at [urlForKeycloak].
 */
class HttpRecorder : AutoCloseable {

    enum class Mode {
        /** Answers 200 at once. */
        OK,

        /** Answers 500, which the plugin retries like any other failure. */
        FAIL,

        /** Reads the request and never answers: the client waits for its own timeout. */
        HANG,

        // No "drop the connection" mode on purpose: the recorder sits behind Testcontainers' SSH tunnel to
        // the host, and hard-dropped connections leave that tunnel broken for the rest of the run. For the
        // plugin a dropped connection and an error answer take the same path anyway (retry after 1 s).
    }

    class Received(val atMs: Long, val method: String, val path: String?, val authorization: String?, val contentType: String?, val body: JsonObject) {
        val type: String get() = body["type"].asString
        val eventId: String get() = body["id"].asString
        val username: String? get() = body.getAsJsonObject("details")?.get("username")?.asString
    }

    @Volatile
    var mode = Mode.OK

    private val received = ConcurrentLinkedQueue<Received>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += Received(
                    System.currentTimeMillis(),
                    request.method ?: "",
                    request.path,
                    request.getHeader("Authorization"),
                    request.getHeader("Content-Type"),
                    JsonParser.parseString(request.body.readUtf8()).asJsonObject,
                )
                return when (mode) {
                    Mode.OK -> MockResponse().setResponseCode(200)
                    Mode.FAIL -> MockResponse().setResponseCode(500)
                    Mode.HANG -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
        }
        start()
    }

    init {
        Testcontainers.exposeHostPorts(server.port)
    }

    val urlForKeycloak = "http://host.testcontainers.internal:${server.port}"

    val requests: List<Received> get() = received.toList()

    /** Distinct events per type, whatever the number of attempts per event. */
    fun distinctByType(): Map<String, Int> = requests.distinctBy { it.eventId }.groupingBy { it.type }.eachCount()

    fun clear() = received.clear()

    override fun close() = server.shutdown()
}

/**
 * Stoplight Prism serving the plugin's own OpenAPI spec, as upstream's compose.yaml does, but with
 * `--errors`: every request is checked against the spec and a violation is answered with an error
 * instead of a mock response. Reachable as http://prism:4010 on the stack's network.
 */
fun prismMock(network: Network): Stack.Container = Stack.Container("stoplight/prism:5")
    .withNetwork(network)
    .withNetworkAliases("prism")
    .withCopyFileToContainer(MountableFile.forHostPath(File(ItSettings.openapiSpec).toPath()), "/tmp/webhook.open-api.yml")
    // Prism 5's multiprocess mode crashes on start in this image ("reading 'isPrimary'"); one process is plenty here.
    .withCommand("mock", "-h", "0.0.0.0", "--errors", "--multiprocess=false", "/tmp/webhook.open-api.yml")
    .withExposedPorts(4010)
    .waitingFor(Wait.forLogMessage(".*Prism is listening.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

/** What Prism's log says about the requests it got: how many, and how many broke the spec. */
class PrismVerdict(logs: String) {
    private val lines = logs.lines()
    val received = lines.count { "Request received" in it }
    val violations = lines.filter { "[VALIDATOR]" in it && "✖" in it }
    override fun toString() = "$received requests, ${violations.size} violations${violations.take(3).joinToString("") { "\n  $it" }}"
}
