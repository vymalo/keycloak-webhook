package com.vymalo.keycloak.webhook

import com.cloudbees.syslog.sender.AbstractSyslogMessageSender
import com.cloudbees.syslog.sender.TcpSyslogMessageSender
import com.cloudbees.syslog.sender.UdpSyslogMessageSender
import com.google.gson.Gson
import com.vymalo.keycloak.webhook.models.SyslogConfig
import org.slf4j.LoggerFactory


class SyslogWebhookHandler : WebhookHandler {
    private lateinit var messageSender: AbstractSyslogMessageSender

    companion object {
        const val PROVIDER_ID = "webhook-syslog"

        @JvmStatic
        private val gson = Gson()

        @JvmStatic
        private val logger = LoggerFactory.getLogger(SyslogWebhookHandler::class.java)
    }

    override fun sendWebhook(request: WebhookPayload) {
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
        runCatching {
            messageSender.close()
        }.onFailure { logger.warn("Error closing channel", it) }
    }

    override fun initHandler() {
        val syslogConfig = SyslogConfig.fromEnv()

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

        this.messageSender = messageSender
    }
}