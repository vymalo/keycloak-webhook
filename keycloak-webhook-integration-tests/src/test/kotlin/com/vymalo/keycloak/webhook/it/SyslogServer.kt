package com.vymalo.keycloak.webhook.it

import com.github.dockerjava.api.command.InspectContainerResponse
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.time.Duration

/**
 * syslog-ng as upstream's compose.yaml runs it (the linuxserver image, UDP 5514 and TCP 6601 through
 * the `syslog()` driver), with one addition: every message is also written exactly as it arrived,
 * so a scenario can check the wire format field by field, not only what syslog-ng made of it.
 * Reachable as `syslog-ng` on the stack's network.
 */
object SyslogServer {
    const val ALIAS = "syslog-ng"
    const val UDP_PORT = 5514
    const val TCP_PORT = 6601
    private const val RAW_LOG = "/config/log/raw.log"
    private const val PARSED_LOG = "/config/log/parsed.log"

    private val config = """
        @version: 4.2
        @include "scl.conf"
        options { keep-hostname(yes); create-dirs(yes); };
        source s_udp { syslog(transport(udp) port($UDP_PORT) flags(store-raw-message)); };
        source s_tcp { syslog(transport(tcp) port($TCP_PORT) flags(store-raw-message)); };
        destination d_raw { file("$RAW_LOG" template("${'$'}{RAWMSG}\n")); };
        destination d_parsed { file("$PARSED_LOG" template("${'$'}HOST ${'$'}PROGRAM ${'$'}MSG\n")); };
        log { source(s_udp); source(s_tcp); destination(d_raw); destination(d_parsed); };
    """.trimIndent()

    /**
     * The image keeps its config in /config, which it declares a volume: anything copied there before
     * the container starts is hidden by the fresh volume. So the config goes in once the container
     * runs, and syslog-ng reloads it.
     */
    fun container(network: Network): Stack.Container = object : Stack.Container("lscr.io/linuxserver/syslog-ng:latest") {
        override fun containerIsStarted(containerInfo: InspectContainerResponse) {
            copyFileToContainer(Transferable.of(config), "/config/syslog-ng.conf")
            val reload = execInContainer("syslog-ng-ctl", "reload", "--control=/config/syslog-ng.ctl")
            check(reload.exitCode == 0) { "syslog-ng didn't take the test config: ${reload.stderr}${reload.stdout}" }
        }
    }
        .withNetwork(network)
        .withNetworkAliases(ALIAS)
        .withEnv("TZ", "Etc/UTC")
        .waitingFor(Wait.forLogMessage(".*\\[ls\\.io-init\\] done.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

    /** The plugin's settings for sending to this server over [protocol], the way the README configures it. */
    fun pluginSettings(protocol: String) = mapOf(
        "WEBHOOK_SYSLOG_PROTOCOL" to protocol,
        "WEBHOOK_SYSLOG_HOSTNAME" to "keycloak-it",
        "WEBHOOK_SYSLOG_APP_NAME" to "keycloak_events",
        "WEBHOOK_SYSLOG_FACILITY" to "USER",
        "WEBHOOK_SYSLOG_SEVERITY" to "INFORMATIONAL",
        "WEBHOOK_SYSLOG_SERVER_HOSTNAME" to ALIAS,
        "WEBHOOK_SYSLOG_SERVER_PORT" to (if (protocol == "tcp") "$TCP_PORT" else "$UDP_PORT").toString(),
        "WEBHOOK_SYSLOG_MESSAGE_FORMAT" to (if (protocol == "tcp") "RFC_5425" else "RFC_5424"),
    )

    /** Every message as it arrived, one per line. */
    fun rawMessages(server: Stack.Container): List<String> = shell(server, "cat $RAW_LOG 2>/dev/null").lines().filter { it.isNotBlank() }

    /**
     * Distinct events per type in everything that arrived, counted inside the container so 50k events
     * stay cheap. Each message has exactly one top-level `"type":"…"` and one `"id":"…"` (detail keys
     * such as `auth_type` or `code_id` don't match the quoted names).
     */
    fun distinctByType(server: Stack.Container): Map<String, Int> =
        shell(server, """sed -n 's/.*"type":"\([^"]*\)".*"id":"\([^"]*\)".*/\2 \1/p' $RAW_LOG 2>/dev/null | sort -u | awk '{print ${'$'}2}' | sort | uniq -c""")
            .lines().filter { it.isNotBlank() }
            .associate { line -> line.trim().split(' ', limit = 2).let { (count, type) -> type to count.toInt() } }

    /** How many messages syslog-ng accepted and stored, parsed. */
    fun parsedCount(server: Stack.Container): Int = shell(server, "wc -l < $PARSED_LOG 2>/dev/null || echo 0").trim().toIntOrNull() ?: 0

    /**
     * Empties the logs without creating them: this runs as root, and a file root creates first is one
     * syslog-ng (running as `abc`) can't write to. syslog-ng creates them itself on the first message.
     */
    fun clear(server: Stack.Container) {
        shell(server, "for f in $RAW_LOG $PARSED_LOG; do [ -f \"${'$'}f\" ] && : > \"${'$'}f\"; done; true")
    }

    private fun shell(server: Stack.Container, command: String): String = server.execInContainer("sh", "-c", command).stdout
}
