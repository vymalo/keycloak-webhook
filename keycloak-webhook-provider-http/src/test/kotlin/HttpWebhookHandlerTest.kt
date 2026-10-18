package com.vymalo.keycloak.webhook

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import com.vymalo.keycloak.webhook.helper.httpBaseBathKey
import com.vymalo.keycloak.webhook.helper.httpQueueCapacityKey
import com.vymalo.keycloak.webhook.models.HttpConfig
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpWebhookHandlerTest {
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
        }

    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private fun handler(vararg urls: String, threads: Int = 1, queueCapacity: Int = 1000) = HttpWebhookHandler().apply {
        initHandler(HttpConfig(null, null, urls.toList(), threads, queueCapacity))
    }

    private fun executor(handler: HttpWebhookHandler): ThreadPoolExecutor =
        HttpWebhookHandler::class.java.getDeclaredField("executor").let {
            it.isAccessible = true
            it.get(handler) as ThreadPoolExecutor
        }

    @Test
    fun `slow endpoint does not block the caller or another available worker`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = LinkedBlockingQueue<String>()
        val slow = server { exchange ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val fast = server { exchange ->
            received.offer(exchange.requestBody.bufferedReader().readText())
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val handler = handler(url(slow), url(fast), threads = 2)
        try {
            handler.sendWebhook(WebhookPayload(type = "LOGIN", realmId = "realm", sessionId = "session"))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val body = assertNotNull(received.poll(5, TimeUnit.SECONDS))
            assertEquals("session", JsonParser.parseString(body).asJsonObject["sessionId"].asString)
        } finally {
            release.countDown()
            executor(handler).shutdownNow()
            slow.stop(0)
            fast.stop(0)
        }
    }

    @Test
    fun `full queue drops deliveries and queued event details are captured`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = LinkedBlockingQueue<String>()
        val count = AtomicInteger()
        val server = server { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            if (count.incrementAndGet() == 1) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            received.offer(body)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val handler = handler(url(server), queueCapacity = 2)
        try {
            handler.sendWebhook(WebhookPayload(type = "LOGIN", realmId = "realm"))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val details = mutableMapOf<String, Any>("value" to "original")
            val payload = WebhookPayload(type = "LOGIN", realmId = "realm", details = details)
            repeat(3) { handler.sendWebhook(payload) }
            details["value"] = "changed"
            assertEquals(2, executor(handler).queue.size)
            release.countDown()
            assertNotNull(received.poll(5, TimeUnit.SECONDS))
            val queued = assertNotNull(received.poll(5, TimeUnit.SECONDS))
            assertEquals("original", JsonParser.parseString(queued).asJsonObject["details"].asJsonObject["value"].asString)
        } finally {
            executor(handler).shutdownNow()
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `queue capacity defaults and validates configured values`() {
        fun capacity(value: String?): Int = HttpConfig.fromConfig { key ->
            when (key) {
                httpBaseBathKey -> "http://localhost"
                httpQueueCapacityKey -> value
                else -> null
            }
        }.queueCapacity

        assertEquals(1000, capacity(null))
        assertEquals(1000, capacity("invalid"))
        assertEquals(1000, capacity("2147483648"))
        assertEquals(1, capacity("0"))
        assertEquals(1, capacity("-1"))
        assertEquals(25, capacity("25"))
    }

    @Test
    fun `failed deliveries are retried up to three attempts`() {
        val attempts = AtomicInteger()
        val completed = CountDownLatch(1)
        val server = server { exchange ->
            val attempt = attempts.incrementAndGet()
            exchange.sendResponseHeaders(if (attempt < 3) 503 else 204, -1)
            exchange.close()
            if (attempt == 3) completed.countDown()
        }
        val handler = handler(url(server))
        try {
            handler.sendWebhook(WebhookPayload(type = "LOGIN", realmId = "realm"))
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(3, attempts.get())
        } finally {
            executor(handler).shutdownNow()
            server.stop(0)
        }
    }
}
