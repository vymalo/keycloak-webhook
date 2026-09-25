package com.vymalo.keycloak.webhook.it

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rabbitmq.client.Connection
import com.rabbitmq.client.DeliverCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listens on the plugin's exchange from the outside, like a real consumer, and records
 * every message: what arrived, how often, with which routing key and message id. Scenarios
 * compare that with what they made Keycloak do.
 */
class Observer(stack: Stack, bindingKey: String = "#") : AutoCloseable {

    class Received(val routingKey: String, val messageId: String?, val payload: JsonObject) {
        val eventId: String get() = payload["id"].asString
        val type: String get() = payload["type"].asString
        val username: String? get() = payload.getAsJsonObject("details")?.get("username")?.asString
    }

    private val connection: Connection = stack.brokerConnectionFactory().newConnection("it-observer")
    private val channel = connection.createChannel()
    private val all = ConcurrentLinkedQueue<Received>()
    private val copies = ConcurrentHashMap<String, AtomicInteger>()

    /** Exclusive and auto-deleted: consumed as messages arrive, and gone with the connection. */
    private val queue: String = channel.queueDeclare().queue

    init {
        channel.queueBind(queue, Stack.EXCHANGE, bindingKey)
        val onMessage = DeliverCallback { _, delivery ->
            val received = Received(
                delivery.envelope.routingKey,
                delivery.properties.messageId,
                JsonParser.parseString(String(delivery.body, Charsets.UTF_8)).asJsonObject,
            )
            all += received
            copies.computeIfAbsent(received.eventId) { AtomicInteger() }.incrementAndGet()
        }
        channel.basicConsume(queue, true, onMessage) { _ -> }
    }

    val messages: List<Received> get() = all.toList()
    val distinctEvents: Int get() = copies.size
    val duplicates: Int get() = copies.values.sumOf { it.get() - 1 }

    /** Distinct events per type: what arrived at least once. */
    fun distinctByType(): Map<String, Int> =
        all.distinctBy { it.eventId }.groupingBy { it.type }.eachCount()

    /** Waits until at least [count] distinct events arrived; returns false if they didn't within [timeoutMs]. */
    fun awaitDistinct(count: Int, timeoutMs: Long): Boolean = awaitCondition(timeoutMs) { distinctEvents >= count }

    fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /** Messages still sitting in the queue; 0 once the consumer has taken everything the broker holds. */
    fun backlog(): Long = connection.createChannel().use { it.messageCount(queue) }

    override fun close() = connection.close()
}
