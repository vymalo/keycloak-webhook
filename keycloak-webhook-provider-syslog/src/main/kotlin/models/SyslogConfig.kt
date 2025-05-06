package com.vymalo.keycloak.webhook.models

import com.cloudbees.syslog.Facility
import com.cloudbees.syslog.MessageFormat
import com.cloudbees.syslog.Severity
import com.vymalo.keycloak.webhook.helper.*

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
        fun fromEnv(): SyslogConfig = SyslogConfig(
            protocol = syslogProtocol.cff().uppercase(),
            hostname = syslogHostname.cff(),
            appName = syslogAppName.cff(),
            facility = Facility.valueOf(syslogFacility.cfe { Facility.SYSLOG.name }),
            severity = Severity.valueOf(syslogSeverity.cfe { Severity.INFORMATIONAL.name }),
            serverHostname = syslogServerHostname.cff(),
            serverPort = syslogServerPort.cff(),
            messageFormat = MessageFormat.valueOf(syslogMessageFormat.cfe { MessageFormat.RFC_5424.name }),
        )
    }
}