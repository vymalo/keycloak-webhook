package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.withConfig
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TCP framing byte for byte. With RFC 5425 every frame is `<length> <message>` and the next one
 * starts right after it: nothing in between, since strict receivers such as syslog-ng read any
 * extra byte as the next frame's header and drop the connection. Newline-delimited formats over
 * TCP keep their newline, because there it is the frame boundary.
 */
class SyslogFramingTest {

    private val loopback = InetAddress.getLoopbackAddress()

    /** Everything the plugin writes over one TCP connection for two sessions, until the factory closes it. */
    private fun bytesSent(format: String?): String {
        ServerSocket(0, 50, loopback).use { server ->
            var received = ""
            val reader = thread(isDaemon = true) {
                server.accept().use { socket -> received = socket.getInputStream().readBytes().toString(Charsets.UTF_8) }
            }
            withConfig(
                syslogProtocol to "tcp",
                syslogHostname to "keycloak",
                syslogAppName to "keycloak_events",
                syslogServerHostname to loopback.hostAddress,
                syslogServerPort to server.localPort.toString(),
                syslogMessageFormat to format,
            ) {
                val factory = SyslogWebhookFactory()
                factory.inSession { it.onEvent(Fixtures.loginEvent()) }
                factory.inSession { it.onEvent(Fixtures.adminEvent(), true) }
                factory.close()
            }
            reader.join(5_000)
            return received
        }
    }

    @Test
    fun `RFC 5425 frames follow each other with nothing in between`() {
        val stream = bytesSent(format = null) // RFC 5425 is the default

        var rest = stream
        val frames = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            val length = rest.substringBefore(' ')
            assertTrue(length.isNotEmpty() && length.all(Char::isDigit), "expected a frame length at: ${rest.take(40)}")
            val start = length.length + 1
            val bytes = rest.substring(start).toByteArray(Charsets.UTF_8)
            val frame = String(bytes, 0, length.toInt(), Charsets.UTF_8)
            frames += frame
            rest = String(bytes, length.toInt(), bytes.size - length.toInt(), Charsets.UTF_8)
        }
        assertEquals(2, frames.size, "frames in: $stream")
        assertTrue(frames[0].endsWith(" " + Fixtures.PayloadJson.LOGIN), frames[0])
        assertTrue(frames[1].endsWith(" " + Fixtures.PayloadJson.ADMIN), frames[1])
    }

    @Test
    fun `newline-delimited formats over TCP keep their newline`() {
        val stream = bytesSent(format = "RFC_5424")
        val lines = stream.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size, "lines in: $stream")
        assertTrue(lines[0].endsWith(" " + Fixtures.PayloadJson.LOGIN), lines[0])
    }
}
