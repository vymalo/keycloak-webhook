package com.vymalo.keycloak.webhook.models

import com.cloudbees.syslog.Facility
import com.cloudbees.syslog.MessageFormat
import com.cloudbees.syslog.Severity
import com.vymalo.keycloak.webhook.helper.*

/** Everything the Syslog transport needs, parsed and validated up front. */
data class SyslogConfig(
    val protocol: Protocol,
    /** This Keycloak's name, sent in every message's HOSTNAME field so the syslog server can tell senders apart. */
    val hostname: String,
    val appName: String,
    val facility: Facility,
    val severity: Severity,
    val serverHostname: String,
    val serverPort: Int,
    val messageFormat: MessageFormat,
) {
    enum class Protocol { TCP, UDP }

    companion object {
        fun from(source: ConfigSource): SyslogConfig = source.read {
            SyslogConfig(
                protocol = enum(syslogProtocol, Protocol.values(), ignoreCase = true),
                hostname = required(syslogHostname),
                appName = required(syslogAppName),
                facility = enum(syslogFacility, Facility.values(), default = Facility.SYSLOG),
                severity = enum(syslogSeverity, Severity.values(), default = Severity.INFORMATIONAL),
                serverHostname = required(syslogServerHostname),
                serverPort = int(syslogServerPort),
                messageFormat = enum(syslogMessageFormat, MessageFormat.values(), default = MessageFormat.RFC_5425),
            )
        }
    }
}
