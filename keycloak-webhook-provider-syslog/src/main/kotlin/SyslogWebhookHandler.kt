package com.vymalo.keycloak.webhook.syslog

import com.cloudbees.syslog.sender.AbstractSyslogMessageSender
import com.cloudbees.syslog.sender.TcpSyslogMessageSender
import com.cloudbees.syslog.sender.UdpSyslogMessageSender
import com.google.gson.Gson
import com.vymalo.keycloak.webhook.core.WebhookHandler
import com.vymalo.keycloak.webhook.core.WebhookPayload
import com.vymalo.keycloak.webhook.syslog.models.SyslogConfig
import org.keycloak.models.KeycloakSession
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap


class SyslogWebhookHandler : WebhookHandler {
    companion object {
        const val PROVIDER_ID = "webhook-syslog"

        @JvmStatic
        private val gson = Gson()

        @JvmStatic
        private val logger = LoggerFactory.getLogger(SyslogWebhookHandler::class.java)

        @JvmStatic
        private val messageSenderCache = ConcurrentHashMap<SyslogConfig, AbstractSyslogMessageSender>()
    }

    override fun sendWebhook(session: KeycloakSession, request: WebhookPayload) {
        val messageSender = getOrCreateMessageSender(SyslogConfig.from(session, request.clientId))

        try {
            val requestStr = gson.toJson(request)
            messageSender.sendMessage(requestStr)

            logger.debug("Webhook message sent: {}", request)
        } catch (ex: Exception) {
            logger.error("Failed to send webhook message", ex)
        }
    }

    override fun getId(): String = PROVIDER_ID

    override fun close() {
    }

    private fun getOrCreateMessageSender(config: SyslogConfig): AbstractSyslogMessageSender {
        return messageSenderCache.computeIfAbsent(config) { syslogConfig ->
            val messageSender = when (syslogConfig.protocol) {
                "TCP" -> TcpSyslogMessageSender()
                "UDP" -> UdpSyslogMessageSender()
                else -> throw RuntimeException("Protocol unknown ${syslogConfig.protocol}")
            }

            messageSender.defaultMessageHostname = syslogConfig.serverHostname
            messageSender.defaultAppName = syslogConfig.appName
            messageSender.defaultFacility = syslogConfig.facility
            messageSender.defaultSeverity = syslogConfig.severity
            messageSender.setSyslogServerHostname(syslogConfig.serverHostname)
            messageSender.setSyslogServerPort(syslogConfig.serverPort.toInt())
            messageSender.messageFormat = syslogConfig.messageFormat
            messageSender
        }
    }
}
