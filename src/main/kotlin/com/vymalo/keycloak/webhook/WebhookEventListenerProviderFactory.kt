package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.webhook.helper.*
import com.vymalo.keycloak.webhook.model.ClientConfig
import com.vymalo.keycloak.webhook.service.AmqpWebhookHandler
import com.vymalo.keycloak.webhook.service.HttpWebhookHandler
import com.vymalo.keycloak.webhook.service.WebhookHandler
import org.keycloak.Config
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ServerInfoAwareProviderFactory
import org.slf4j.LoggerFactory


class WebhookEventListenerProviderFactory : EventListenerProviderFactory, ServerInfoAwareProviderFactory {

    companion object {
        private val LOG = LoggerFactory.getLogger(WebhookEventListenerProviderFactory::class.java)

        const val PROVIDER_ID = "listener-webhook"
    }

    private var clientConfig: ClientConfig = ClientConfig()
    private var handlers: HashSet<WebhookHandler>? = null

    override fun create(session: KeycloakSession): EventListenerProvider {
        ensureParametersInit()
        return WebhookEventListenerProvider(handlers, clientConfig.takeList)
    }

    @Synchronized
    private fun ensureParametersInit() {
        synchronized(clientConfig) {
            if (handlers == null) {
                val handlers = HashSet<WebhookHandler>()
                val amqpConfig = clientConfig.amqp
                if (amqpConfig != null) {
                    try {
                        val handler = AmqpWebhookHandler(amqp = amqpConfig)
                        handlers.add(handler)
                    } catch (e: Throwable) {
                        LOG.error("Error while creating AMQP handler", e)
                    }
                }

                val httpConfig = clientConfig.http
                if (httpConfig != null) {
                    val handler = HttpWebhookHandler(httpConfig)
                    handlers.add(handler)
                }

                LOG.info("Added {} listener webhook handler", handlers.map { it.handler() })
                this.handlers = handlers
            }
        }
    }

    override fun init(config: Config.Scope) {
        val tl = eventsTakenKey.cf()
        if (tl != null) {
            clientConfig.takeList = tl.trim().split(",").toSet()
            LOG.debug("Will handle these events : {}", clientConfig.takeList)
        }

        val basePath = httpBaseBathKey.cf()
        if (basePath != null) {
            LOG.debug("Will send http requests to {}", basePath)
            clientConfig.http = ClientConfig.Companion.Http(
                username = httpAuthUsernameKey.cf(),
                password = httpAuthPasswordKey.cf(),
                baseUrl = basePath,
            )
        }

        val amqpHost = amqpHostKey.cf()
        if (amqpHost != null) {
            clientConfig.amqp = ClientConfig.Companion.Amqp(
                username = amqpUsernameKey.cff(),
                password = amqpPasswordKey.cff(),
                host = amqpHost,
                port = amqpPortKey.cff(),
                vHost = amqpVHostKey.cf(),
                ssl = amqpSsl.bf(),
                exchange = amqpExchangeKey.cff()
            )
        }
        LOG.info("Webhook plugin init!")
    }

    override fun postInit(factory: KeycloakSessionFactory) {}

    override fun close() {
        handlers?.forEach {
            try {
                it.close()
            } catch (e: Throwable) {
                LOG.error("Could not close correctly", e)
            }
        }
    }

    override fun getId(): String = PROVIDER_ID

    override fun getOperationalInfo() = mapOf("version" to "0.3.0")

}