package com.vymalo.keycloak.webhook.testing

import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import java.lang.reflect.Proxy

/**
 * Just enough of Keycloak's provider lifecycle to drive a factory the way the
 * server does: one `create()` per Keycloak session, the event(s), then `close()`
 * on the provider when the session ends.
 *
 * Tests go through this instead of calling handlers directly, so they only see
 * what Keycloak would see and survive any rework of the classes underneath.
 */
object KeycloakSim {

    /**
     * No provider in this plugin reads from the session, so a proxy that answers
     * null to everything is enough. If one ever does, this fails loudly in tests.
     */
    private val session: KeycloakSession = Proxy.newProxyInstance(
        KeycloakSession::class.java.classLoader,
        arrayOf(KeycloakSession::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "KeycloakSim.session"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as KeycloakSession

    /** Runs [block] inside one simulated Keycloak session and closes the provider afterwards, like Keycloak does. */
    fun <T> EventListenerProviderFactory.inSession(block: (EventListenerProvider) -> T): T {
        val provider = create(session)
        try {
            return block(provider)
        } finally {
            provider.close()
        }
    }
}

/**
 * Sets plugin config for the duration of [block]. The plugin reads env vars first
 * and falls back to system properties, so tests use the latter; everything is put
 * back afterwards so tests can't leak settings into each other.
 */
fun <T> withConfig(vararg entries: Pair<String, String?>, block: () -> T): T {
    val previous = entries.associate { (key, _) -> key to System.getProperty(key) }
    entries.forEach { (key, value) -> setOrClear(key, value) }
    try {
        return block()
    } finally {
        previous.forEach { (key, value) -> setOrClear(key, value) }
    }
}

private fun setOrClear(key: String, value: String?) {
    if (value == null) System.clearProperty(key) else System.setProperty(key, value)
}

/** Polls [probe] until it returns non-null, for things that arrive asynchronously (brokers, sockets). */
fun <T : Any> eventually(timeoutMs: Long = 10_000, probe: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        probe()?.let { return it }
        Thread.sleep(50)
    }
    throw AssertionError("Nothing arrived within ${timeoutMs}ms")
}
