package com.vymalo.keycloak.webhook.syslog.models

import com.cloudbees.syslog.Facility
import com.cloudbees.syslog.MessageFormat
import com.cloudbees.syslog.Severity
import com.vymalo.keycloak.webhook.core.helper.*
import org.keycloak.models.KeycloakSession

data class SyslogConfig(
    val protocol: String,
    val hostname: String,
    val appName: String,
    val facility: Facility,
    val severity: Severity,
    val serverHostname: String,
    val serverPort: String,
    val messageFormat: MessageFormat,
) {
    companion object {
        fun from(session: KeycloakSession, clientId: String?): SyslogConfig {
            val config = ClientAttributeConfig.from(session, clientId)
            return SyslogConfig(
                protocol = config.requiredString(syslogProtocol).uppercase(),
                hostname = config.requiredString(syslogHostname),
                appName = config.requiredString(syslogAppName),
                facility = Facility.valueOf(config.string(syslogFacility) ?: Facility.SYSLOG.name),
                severity = Severity.valueOf(config.string(syslogSeverity) ?: Severity.INFORMATIONAL.name),
                serverHostname = config.requiredString(syslogServerHostname),
                serverPort = config.requiredString(syslogServerPort),
                messageFormat = MessageFormat.valueOf(config.string(syslogMessageFormat) ?: MessageFormat.RFC_5425.name),
            )
        }
    }
}
