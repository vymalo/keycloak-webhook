package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.eventually
import com.vymalo.keycloak.webhook.testing.withConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TCP sender used to be rebuilt, and its socket reopened, for every Keycloak session. */
class SyslogLifecycleTest {

    @Test
    fun `one TCP connection serves every session and is closed with the factory`() {
        val loopback = InetAddress.getLoopbackAddress()
        val accepted = CopyOnWriteArrayList<Socket>()

        ServerSocket(0, 50, loopback).use { server ->
            thread(isDaemon = true) {
                while (!server.isClosed) accepted += runCatching { server.accept() }.getOrNull() ?: break
            }

            withConfig(
                syslogProtocol to "tcp",
                syslogHostname to "keycloak",
                syslogAppName to "keycloak_events",
                syslogServerHostname to loopback.hostAddress,
                syslogServerPort to server.localPort.toString(),
            ) {
                val factory = SyslogWebhookFactory()
                repeat(3) { factory.inSession { it.onEvent(Fixtures.loginEvent()) } }
                eventually { accepted.takeIf { it.isNotEmpty() } }
                assertEquals(1, accepted.size)

                factory.close()
                // Draining to end-of-stream proves the sender hung up; a socket still open
                // would hit the read timeout instead and fail the test.
                val socket = accepted.single().apply { soTimeout = 5_000 }
                val frames = String(socket.getInputStream().readBytes(), Charsets.UTF_8)
                assertEquals(3, frames.split(Fixtures.PayloadJson.LOGIN).size - 1)
            }
            accepted.forEach { it.close() }
        }
    }
}
