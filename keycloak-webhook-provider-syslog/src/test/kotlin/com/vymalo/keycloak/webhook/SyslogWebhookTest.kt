package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.withConfig
import org.junit.jupiter.api.AfterEach
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the Syslog provider sends, checked against local UDP and TCP listeners.
 * The payload JSON is the message body; the header is pinned only as far as it
 * is deterministic (the timestamp is not).
 */
class SyslogWebhookTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeListeners() = closeables.forEach { runCatching { it.close() } }

    private fun <T> withSyslog(protocol: String, port: Int, vararg extra: Pair<String, String?>, block: () -> T): T =
        withConfig(
            syslogProtocol to protocol,
            syslogHostname to "keycloak",
            syslogAppName to "keycloak_events",
            syslogFacility to "USER",
            syslogSeverity to "INFORMATIONAL",
            syslogServerHostname to loopback.hostAddress,
            syslogServerPort to port.toString(),
            *extra,
            block = block,
        )

    /**
     * `<14>` is facility USER (1) * 8 + severity INFORMATIONAL (6); the hostname field carries
     * WEBHOOK_SYSLOG_HOSTNAME. (It used to carry the syslog server's address, which this pinned
     * until the fix.)
     */
    private fun rfc5424(json: String) =
        Regex("""^<14>1 \S+ keycloak keycloak_events - - - ${Regex.escape(json)}$""")

    @Test
    fun `UDP sends one RFC 5424 datagram per event`() {
        val socket = DatagramSocket(0, loopback).apply { soTimeout = 5_000 }.also { closeables += it }

        withSyslog("udp", socket.localPort, syslogMessageFormat to "RFC_5424") {
            SyslogWebhookFactory().inSession { it.onEvent(Fixtures.loginEvent()) }
        }

        val packet = DatagramPacket(ByteArray(8_192), 8_192)
        socket.receive(packet)
        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
        assertTrue(rfc5424(Fixtures.PayloadJson.LOGIN).matches(message), message)
    }

    @Test
    fun `TCP uses octet-counted framing by default and keeps working across sessions`() {
        val frames = tcpListener()

        withSyslog("tcp", frames.port, syslogMessageFormat to null) {
            val factory = SyslogWebhookFactory()
            factory.inSession { it.onEvent(Fixtures.loginEvent()) }
            factory.inSession { it.onEvent(Fixtures.adminEvent(), true) }
        }

        // Upstream opens a new connection per session, so arrival order across
        // sessions is up to the network; compare the bodies as a set.
        val messages = List(2) { assertNotNull(frames.received.poll(5, TimeUnit.SECONDS)) }
        assertEquals(
            setOf(Fixtures.PayloadJson.LOGIN, Fixtures.PayloadJson.ADMIN),
            messages.map { it.substringAfter(" - - - ") }.toSet(),
        )
        messages.forEach { assertEquals("<14>1", it.substringBefore(' '), it) }
    }

    private class TcpFrames(val port: Int, val received: LinkedBlockingQueue<String>)

    /**
     * Accepts any number of connections and splits RFC 6587 octet-counted frames
     * (`<length> <message>`), since the sender may or may not reuse its socket.
     * The library also writes CRLF after every frame, which is skipped here.
     */
    private fun tcpListener(): TcpFrames {
        val server = ServerSocket(0, 50, loopback).also { closeables += it }
        val received = LinkedBlockingQueue<String>()
        thread(isDaemon = true, name = "syslog-test-accept") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                closeables += socket
                thread(isDaemon = true, name = "syslog-test-read") {
                    val input = DataInputStream(socket.getInputStream())
                    while (true) {
                        val length = readLength(input) ?: break
                        val bytes = ByteArray(length).also { input.readFully(it) }
                        received += String(bytes, Charsets.UTF_8)
                    }
                }
            }
        }
        return TcpFrames(server.localPort, received)
    }

    /** Reads the decimal length before the space, or null once the peer hangs up. */
    private fun readLength(input: DataInputStream): Int? {
        val digits = StringBuilder()
        while (true) {
            val byte = runCatching { input.read() }.getOrDefault(-1)
            when {
                byte < 0 -> return null
                byte == ' '.code -> return digits.toString().toInt()
                byte.toChar().isDigit() -> digits.append(byte.toChar())
                // CR/LF between frames
                else -> Unit
            }
        }
    }
}
