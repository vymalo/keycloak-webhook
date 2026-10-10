package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.testing.Fixtures
import com.vymalo.keycloak.webhook.testing.KeycloakSim.inSession
import com.vymalo.keycloak.webhook.testing.withConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.images.builder.Transferable
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TLS against a broker with its own CA. The broker makes its certificates at startup
 * with the openssl that ships in the RabbitMQ image, so nothing secret lives in the repo.
 */
class AmqpTlsTest {

    companion object {
        private const val EXCHANGE = "keycloak-tls"
        private const val TLS_PORT = 5671
        private const val STORE_PASSWORD = "changeit"

        /** A CA, a server certificate it signs for localhost/127.0.0.1, and an unrelated second CA. */
        private val makeCertificates = """
            set -e
            mkdir -p /certs && cd /certs
            openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=test-ca -keyout ca.key -out ca.pem
            openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=other-ca -keyout other.key -out other-ca.pem
            openssl req -newkey rsa:2048 -nodes -subj /CN=localhost -keyout server.key -out server.csr
            echo subjectAltName=DNS:localhost,IP:127.0.0.1 > san.ext
            openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -extfile san.ext -out server.pem
            chmod 644 /certs/*
            exec docker-entrypoint.sh rabbitmq-server
        """.trimIndent()

        private val broker = TestBroker {
            withExposedPorts(TestBroker.AMQP_PORT, TLS_PORT)
            withCopyToContainer(
                Transferable.of(
                    """
                    listeners.ssl.default = $TLS_PORT
                    ssl_options.cacertfile = /certs/ca.pem
                    ssl_options.certfile = /certs/server.pem
                    ssl_options.keyfile = /certs/server.key
                    ssl_options.verify = verify_none
                    ssl_options.fail_if_no_peer_cert = false
                    """.trimIndent()
                ),
                "/etc/rabbitmq/conf.d/20-tls.conf",
            )
            withCreateContainerCmdModifier { it.withEntrypoint("sh", "-c", makeCertificates) }
        }

        private lateinit var rightCa: File
        private lateinit var wrongCa: File

        /** Who actually signed the certificate this machine receives; see [interceptedBy]. */
        private lateinit var presentedIssuer: String

        @JvmStatic
        @BeforeAll
        fun startBroker() {
            broker.start()
            rightCa = truststoreWith("/certs/ca.pem")
            wrongCa = truststoreWith("/certs/other-ca.pem")
            presentedIssuer = issuerSeenFromHere()
        }

        /**
         * Antivirus "HTTPS scanning" (AVG, Avast, ...) and corporate proxies can re-sign every
         * TLS connection, even to a local Docker port. A truststore that holds only the broker's
         * CA then refuses the connection, which is exactly what it should do. The test proving
         * delivery with the right CA can't pass through that, so it skips and names the interceptor.
         */
        private fun interceptedBy(): String? = presentedIssuer.takeUnless { it == "CN=test-ca" }

        /** Handshakes once, trusting anything, just to see which issuer the certificate arrives with. */
        private fun issuerSeenFromHere(): String {
            var issuer = "(no certificate)"
            val recordIssuer = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    issuer = chain.first().issuerX500Principal.name
                }
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recordIssuer), null) }
            (context.socketFactory.createSocket(broker.host, broker.getMappedPort(TLS_PORT)) as SSLSocket)
                .use { it.startHandshake() }
            return issuer
        }

        @JvmStatic
        @AfterAll
        fun stopBroker() = broker.stop()

        /** A PKCS12 truststore holding just the CA certificate copied out of the broker. */
        private fun truststoreWith(pemInContainer: String): File {
            val certificate = broker.copyFileFromContainer(pemInContainer) {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("ca", certificate)
            }
            return File.createTempFile("truststore", ".p12").apply {
                deleteOnExit()
                outputStream().use { store.store(it, STORE_PASSWORD.toCharArray()) }
            }
        }
    }

    private fun publishLoginOverTls(vararg tls: Pair<String, String?>) = withConfig(
        amqpHostKey to broker.host,
        amqpPortKey to broker.getMappedPort(TLS_PORT).toString(),
        amqpUsernameKey to TestBroker.USER,
        amqpPasswordKey to TestBroker.PASSWORD,
        amqpExchangeKey to EXCHANGE,
        amqpSsl to "true",
        *tls,
    ) {
        val factory = AmqpWebhookFactory()
        try {
            factory.inSession { it.onEvent(Fixtures.loginEvent()) }
        } finally {
            factory.close()
        }
    }

    private fun queuedMessages(queue: String): Int = broker.admin { it.messageCount(queue) }.toInt()

    @Test
    fun `without a truststore TLS still accepts any certificate, as before`() {
        val queue = broker.bindQueue(EXCHANGE)
        publishLoginOverTls()
        assertEquals(Fixtures.PayloadJson.LOGIN, String(broker.nextMessage(queue).body, Charsets.UTF_8))
    }

    @Test
    fun `a truststore with the broker's CA verifies it and delivers`() {
        assumeTrue(interceptedBy() == null) {
            "Skipped: TLS on this machine is re-signed by '${interceptedBy()}' (antivirus HTTPS scanning or a proxy). " +
                "Turn that off, or exclude local traffic from it, to run this test."
        }
        val queue = broker.bindQueue(EXCHANGE)
        publishLoginOverTls(
            amqpSslTruststoreKey to rightCa.path,
            amqpSslTruststorePasswordKey to STORE_PASSWORD,
        )
        assertEquals(Fixtures.PayloadJson.LOGIN, String(broker.nextMessage(queue).body, Charsets.UTF_8))
    }

    @Test
    fun `a truststore with a different CA refuses the broker and drops the event`() {
        val queue = broker.bindQueue(EXCHANGE)
        publishLoginOverTls(
            amqpSslTruststoreKey to wrongCa.path,
            amqpSslTruststorePasswordKey to STORE_PASSWORD,
        )
        assertEquals(0, queuedMessages(queue))
        assertNull(broker.admin { it.basicGet(queue, true) })
    }
}
