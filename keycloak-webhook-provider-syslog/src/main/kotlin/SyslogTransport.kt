package com.vymalo.keycloak.webhook

import com.cloudbees.syslog.sender.AbstractSyslogMessageSender
import com.cloudbees.syslog.sender.TcpSyslogMessageSender
import com.cloudbees.syslog.sender.UdpSyslogMessageSender
import com.google.gson.Gson
import com.vymalo.keycloak.webhook.models.SyslogConfig
import com.vymalo.keycloak.webhook.models.SyslogConfig.Protocol
import org.slf4j.LoggerFactory

/**
 * Sends each event's JSON as one syslog message. The sender is built once; over TCP it
 * keeps its socket between events and reconnects by itself when the server drops it.
 * Both senders are safe to call from several threads.
 */
class SyslogTransport(config: SyslogConfig) : Transport {

    private val sender: AbstractSyslogMessageSender = when (config.protocol) {
        Protocol.TCP -> TcpSyslogMessageSender()
        Protocol.UDP -> UdpSyslogMessageSender()
    }.apply {
        // Upstream sets the HOSTNAME field from the server address, not WEBHOOK_SYSLOG_HOSTNAME.
        // Kept as-is: changing it changes every message, so it will be a deliberate, documented fix.
        defaultMessageHostname = config.serverHostname
        defaultAppName = config.appName
        defaultFacility = config.facility
        defaultSeverity = config.severity
        setSyslogServerHostname(config.serverHostname)
        setSyslogServerPort(config.serverPort)
        messageFormat = config.messageFormat
    }

    override fun publish(payload: WebhookPayload) {
        try {
            sender.sendMessage(gson.toJson(payload))
            logger.debug("Webhook message sent: {}", payload)
        } catch (e: Exception) {
            logger.error("Failed to send webhook message", e)
        }
    }

    override fun close() = sender.close()

    private companion object {
        private val gson = Gson()
        private val logger = LoggerFactory.getLogger(SyslogTransport::class.java)
    }
}
