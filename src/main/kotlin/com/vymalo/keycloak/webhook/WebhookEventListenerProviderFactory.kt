package com.vymalo.keycloak.webhook

import com.vymalo.keycloak.openapi.client.handler.WebhookApi
import com.vymalo.keycloak.openapi.client.infrastructure.ApiClient
import com.vymalo.keycloak.webhook.helper.getConfig
import com.vymalo.keycloak.webhook.service.HttpWebhookService
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

        private const val takeListKey = "WEBHOOK_TAKE_EVENTS"
        private const val baseBathKey = "WEBHOOK_BASE_PATH"
        private const val authUsernameKey = "WEBHOOK_AUTH_USERNAME"
        private const val authPasswordKey = "WEBHOOK_AUTH_PASSWORD"
    }

    private var takeList: Set<String>? = null
    private var basePath: String = "scheme://fake-host:3290"

    override fun create(session: KeycloakSession): EventListenerProvider {
        val api = WebhookApi(basePath)
        val service = HttpWebhookService(api, takeList)
        return WebhookEventListenerProvider(service)
    }

    override fun init(config: Config.Scope) {
        val tl = getConfig(takeListKey)
        if (tl != null) {
            takeList = tl.trim().split(",").toSet()
            LOG.debug("Will handle these events : {}", takeList)
        }

        basePath = getConfig(baseBathKey)!!
        LOG.debug("Will send events to this endpoint : {}", basePath)

        ApiClient.username = getConfig(authUsernameKey)
        ApiClient.password = getConfig(authPasswordKey)
    }

    override fun postInit(factory: KeycloakSessionFactory?) = noop()

    override fun close() = noop()

    override fun getId(): String = PROVIDER_ID

    override fun getOperationalInfo() = mapOf("version" to "0.1.0")

    private fun noop() {}
}