package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.models.AmqpConfig
import com.vymalo.keycloak.webhook.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The parts of the AMQP wire format that don't need a broker: routing keys and message
 * properties. Same golden values as AmqpWebhookTest, so a build without Docker still
 * guards what goes out.
 */
class AmqpWireTest {

    /** The original settings only; every newer option stays off. */
    private fun config(vararg extra: Pair<String, String>): AmqpConfig {
        val entries = mapOf(
            amqpUsernameKey to "keycloak",
            amqpPasswordKey to "secret",
            amqpHostKey to "rabbit",
            amqpPortKey to "5672",
            amqpExchangeKey to "keycloak",
        ) + extra
        return AmqpConfig.from(ConfigSource { entries[it] })
    }

    @Test
    fun `routing keys are KC_CLIENT dot realm, client, user and type`() {
        assertEquals("KC_CLIENT.realm-id.account.user-1.LOGIN", AmqpTransport.routingKey(Fixtures.loginEvent().toPayload()))
        assertEquals("KC_CLIENT.realm-id.admin-cli.admin-1.USER-CREATE", AmqpTransport.routingKey(Fixtures.adminEvent().toPayload()))
    }

    @Test
    fun `missing client and user become xxx`() {
        assertEquals("KC_CLIENT.realm-id.xxx.xxx.LOGIN_ERROR", AmqpTransport.routingKey(Fixtures.anonymousEvent().toPayload()))
    }

    @Test
    fun `message properties are JSON for Spring consumers, transient and without an id`() {
        val props = AmqpTransport.messageProperties(config())
        assertEquals("Keycloak/Kotlin", props.appId)
        assertEquals("application/json", props.contentType)
        assertEquals("UTF-8", props.contentEncoding)
        assertEquals(mapOf<String, Any>("__TypeId__" to "com.vymalo.keycloak.webhook.WebhookPayload"), props.headers)
        assertNull(props.deliveryMode)
        assertNull(props.messageId)
    }

    @Test
    fun `WEBHOOK_AMQP_PERSISTENT changes only the delivery mode`() {
        val plain = AmqpTransport.messageProperties(config())
        val persistent = AmqpTransport.messageProperties(config(amqpPersistentKey to "true"))
        assertEquals(2, persistent.deliveryMode)
        assertEquals(plain.builder().deliveryMode(2).build().toString(), persistent.toString())
    }
}
