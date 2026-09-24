package com.vymalo.keycloak.webhook.models

import com.rabbitmq.client.Address
import com.vymalo.keycloak.webhook.helper.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmqpConfigTest {

    private val minimal = mapOf(
        amqpUsernameKey to "keycloak",
        amqpPasswordKey to "secret",
        amqpHostKey to "rabbit",
        amqpPortKey to "5672",
        amqpExchangeKey to "keycloak",
    )

    private fun parse(vararg overrides: Pair<String, String?>): AmqpConfig {
        val entries = minimal + overrides
        return AmqpConfig.from(ConfigSource { entries[it] })
    }

    @Test
    fun `the original settings alone leave every newer option off`() {
        val config = parse()
        assertEquals(listOf(Address("rabbit", 5672)), config.addresses)
        assertEquals("/", config.vHost)
        assertNull(config.truststore)
        assertNull(config.heartbeatSeconds)
        assertFalse(config.persistent)
        assertFalse(config.messageId)
        assertFalse(config.declareExchange)
    }

    @Test
    fun `addresses replace host and port, which are then not required`() {
        val config = parse(amqpHostKey to null, amqpPortKey to null, amqpAddressesKey to "rabbit-1:5673, rabbit-2")
        assertEquals(listOf(Address("rabbit-1", 5673), Address("rabbit-2", -1)), config.addresses)
    }

    @Test
    fun `a malformed address is reported with the entry`() {
        val error = assertFailsWith<ConfigException> { parse(amqpAddressesKey to "rabbit-1:5673,rabbit-2:abc") }
        assertEquals(
            listOf("$amqpAddressesKey has an invalid entry 'rabbit-2:abc', expected host or host:port"),
            error.problems,
        )
    }

    @Test
    fun `a truststore without TLS switched on is a config error`() {
        val error = assertFailsWith<ConfigException> { parse(amqpSslTruststoreKey to "/etc/ca.p12") }
        assertEquals(listOf("$amqpSslTruststoreKey is set, but $amqpSsl is not \"true\""), error.problems)
    }

    @Test
    fun `truststore type defaults to PKCS12`() {
        val config = parse(amqpSsl to "true", amqpSslTruststoreKey to "/etc/ca.p12")
        assertEquals(AmqpConfig.Truststore("/etc/ca.p12", null, "PKCS12"), config.truststore)
    }

    @Test
    fun `passwords never show up in toString`() {
        val text = parse(amqpSsl to "true", amqpSslTruststoreKey to "/etc/ca.p12", amqpSslTruststorePasswordKey to "hunter2")
            .toString()
        assertFalse("secret" in text || "hunter2" in text, text)
        assertTrue("keycloak" in text)
    }
}
