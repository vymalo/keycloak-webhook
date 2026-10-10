package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.ConfigException
import com.vymalo.keycloak.webhook.helper.ConfigSource
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.errorsLoggedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The fix this rework started from: Keycloak closes each session's provider, and that
 * used to close the transport's connection too, so every session reconnected. The
 * transport now lives from the first session until the factory itself is closed.
 */
class LifecycleTest {

    private class CountingTransport : Transport {
        var published = 0
        var closed = 0
        override fun publish(payload: WebhookPayload) {
            published++
        }
        override fun close() {
            closed++
        }
    }

    private class Factory(open: (ConfigSource) -> Transport) :
        WebhookEventListenerFactory("counting", open, ConfigSource { null })

    @Test
    fun `one transport serves every session and survives their close`() {
        var opened = 0
        val transport = CountingTransport()
        val factory = Factory { opened++; transport }

        repeat(5) { factory.inSession { it.onEvent(Fixtures.loginEvent()) } }

        assertEquals(1, opened)
        assertEquals(5, transport.published)
        assertEquals(0, transport.closed)
    }

    @Test
    fun `closing the factory closes the transport once`() {
        val transport = CountingTransport()
        val factory = Factory { transport }
        factory.inSession { it.onEvent(Fixtures.loginEvent()) }

        factory.close()
        factory.close()

        assertEquals(1, transport.closed)
    }

    @Test
    fun `a factory that never saw a session opens nothing and closes quietly`() {
        var opened = 0
        Factory { opened++; CountingTransport() }.close()
        assertEquals(0, opened)
    }

    @Test
    fun `a config error is logged once, never fails the session, and a later session tries again`() {
        var attempts = 0
        val transport = CountingTransport()
        val factory = Factory {
            if (attempts++ < 2) throw ConfigException(listOf("WEBHOOK_AMQP_HOST is required"))
            transport
        }

        // Keycloak builds listeners while handling a login, so getting past inSession
        // means the login would have gone through. The same error twice is logged once.
        val logged = errorsLoggedBy(WebhookEventListenerFactory::class.java) {
            repeat(2) { factory.inSession { it.onEvent(Fixtures.loginEvent()) } }
        }
        factory.inSession { it.onEvent(Fixtures.loginEvent()) }

        assertIs<ConfigException>(logged.single().thrown)
        assertEquals(3, attempts)
        assertEquals(1, transport.published)
    }
}
