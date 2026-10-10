package com.vymalo.keycloak.webhook

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.vymalo.keycloak.webhook.helper.amqpSsl
import com.vymalo.keycloak.webhook.helper.amqpSslTruststoreKey
import com.vymalo.keycloak.webhook.models.AmqpConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Opens broker connections the way the config asks: credentials, addresses, TLS,
 * heartbeat, and declaring the exchange. Shared by the sync and async transports;
 * [tune] is where they differ (automatic recovery, timeouts). A connect timeout set in
 * the config is applied after [tune], so the operator's value wins in either mode.
 */
internal class AmqpConnections(private val config: AmqpConfig, tune: ConnectionFactory.() -> Unit) {

    /** Internal so tests can check the settings without a broker; nothing connects until [open]. */
    internal val factory = ConnectionFactory().apply {
        username = config.username
        password = config.password
        virtualHost = config.vHost
        config.heartbeatSeconds?.let { requestedHeartbeat = it }
        when {
            config.truststore != null -> {
                useSslProtocol(sslContext(config.truststore))
                enableHostnameVerification()
            }
            config.ssl -> {
                useSslProtocol()
                logger.warn("{} is on without {}: the broker's certificate is not verified", amqpSsl, amqpSslTruststoreKey)
            }
        }
        tune()
        config.connectionTimeoutMs?.let { connectionTimeout = it }
    }

    fun open(): Connection = factory.newConnection(config.addresses).also { if (config.declareExchange) declareExchange(it) }

    /**
     * Declares the exchange on a throwaway channel: if it already exists with other
     * settings, the broker closes the channel it was asked on, and that must not be
     * the one we publish on. That case is only logged; publishing uses it as it is.
     */
    private fun declareExchange(connection: Connection) {
        if (config.exchange.isEmpty()) return // the default exchange always exists and can't be declared
        runCatching { connection.createChannel().use { it.exchangeDeclare(config.exchange, "topic", true) } }
            .onSuccess { logger.debug("Exchange '{}' declared (durable topic)", config.exchange) }
            .onFailure {
                logger.warn("Could not declare exchange '{}', publishing to it as it is: {}", config.exchange, it.message)
            }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AmqpConnections::class.java)

        /**
         * Closes [channel] and [connection] without throwing. A dropped connection is aborted
         * rather than closed: with automatic recovery on it reports closed but keeps
         * reconnecting in the background, and would come back as a leaked second connection.
         * abort() stops that and never throws; close() is kept for open connections so pending
         * frames are flushed. [timeoutMs] bounds the wait for a broker that no longer answers.
         */
        fun shutDown(connection: Connection?, channel: Channel?, timeoutMs: Int = -1) {
            runCatching { channel?.takeIf { it.isOpen }?.close() }
                .onFailure { logger.warn("Error closing channel", it) }
            connection?.let {
                if (it.isOpen) {
                    runCatching { it.close(timeoutMs) }.onFailure { e -> logger.warn("Error closing connection", e) }
                } else {
                    it.abort(timeoutMs)
                }
            }
        }

        /** Trusts exactly the certificates in the configured store; a bad path or password is a setup error, logged once. */
        private fun sslContext(truststore: AmqpConfig.Truststore): SSLContext {
            val store = try {
                KeyStore.getInstance(truststore.type).apply {
                    File(truststore.path).inputStream().use { load(it, truststore.password?.toCharArray()) }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Could not load $amqpSslTruststoreKey '${truststore.path}': ${e.message}", e)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }
    }
}
