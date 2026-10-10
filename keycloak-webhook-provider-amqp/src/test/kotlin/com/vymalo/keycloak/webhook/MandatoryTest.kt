package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.models.AmqpConfig
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.eventually
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WEBHOOK_AMQP_MANDATORY against a real broker. Without it, a message no queue takes is
 * dropped by the broker and still confirmed; with it, the plugin hears about every one.
 */
class MandatoryTest {

    companion object {
        private val broker = TestBroker()

        @JvmStatic
        @BeforeAll
        fun startBroker() = broker.start()

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()
    }

    /** A fresh exchange that exists but has no queue bound: nothing published to it can be routed. */
    private val exchange = "unbound-${UUID.randomUUID()}".also { name -> broker.admin { it.exchangeDeclare(name, "topic", true) } }

    private fun config(vararg overrides: Pair<String, String?>): AmqpConfig {
        val settings = mapOf(
            amqpHostKey to broker.host,
            amqpPortKey to broker.amqpPort.toString(),
            amqpUsernameKey to TestBroker.USER,
            amqpPasswordKey to TestBroker.PASSWORD,
            amqpExchangeKey to exchange,
            amqpEnablePublisherConfirm to "true",
            amqpMandatoryKey to "true",
        ) + overrides
        return AmqpConfig.from(ConfigSource { settings[it] })
    }

    private val login = Fixtures.loginEvent().toPayload()

    @Test
    fun `sync mode reports a message no queue takes`() {
        AmqpTransport(config()).use { transport ->
            transport.publish(login)
            eventually { transport.unroutable.count.get().takeIf { it == 1L } }
        }
    }

    @Test
    fun `async mode reports it too, and neither retries nor resends it`() {
        AsyncAmqpTransport(config(amqpPublishModeKey to "async")).use { transport ->
            transport.publish(login)
            eventually { transport.stats.unroutable.get().takeIf { it == 1L } }
            Thread.sleep(500) // room for a retry loop, if there were one
            assertEquals(1, transport.stats.unroutable.get())
            assertEquals(0, transport.stats.resent.get())
            assertEquals(0, transport.stats.refusedDropped.get())
        }
    }

    @Test
    fun `a message that does reach a queue is not reported`() {
        val queue = broker.bindQueue(exchange)
        AmqpTransport(config()).use { transport ->
            transport.publish(login)
            assertEquals(Fixtures.PayloadJson.LOGIN, String(broker.nextMessage(queue).body, Charsets.UTF_8))
            assertEquals(0, transport.unroutable.count.get())
        }
    }

    @Test
    fun `without the option nothing is reported, as before`() {
        AmqpTransport(config(amqpMandatoryKey to null)).use { transport ->
            transport.publish(login)
            Thread.sleep(500)
            assertEquals(0, transport.unroutable.count.get())
        }
    }
}
