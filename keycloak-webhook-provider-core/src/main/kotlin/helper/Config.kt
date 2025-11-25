package com.vymalo.keycloak.webhook.helper

const val eventsTakenKey = "WEBHOOK_EVENTS_TAKEN"

const val httpBaseBathKey = "WEBHOOK_HTTP_BASE_PATH"
const val httpAuthUsernameKey = "WEBHOOK_HTTP_AUTH_USERNAME"
const val httpAuthPasswordKey = "WEBHOOK_HTTP_AUTH_PASSWORD"

const val amqpUsernameKey = "WEBHOOK_AMQP_USERNAME"
const val amqpPasswordKey = "WEBHOOK_AMQP_PASSWORD"
const val amqpHostKey = "WEBHOOK_AMQP_HOST"
const val amqpPortKey = "WEBHOOK_AMQP_PORT"
const val amqpVHostKey = "WEBHOOK_AMQP_VHOST"
const val amqpExchangeKey = "WEBHOOK_AMQP_EXCHANGE"
const val amqpSsl = "WEBHOOK_AMQP_SSL"
const val amqpEnablePublisherConfirm = "WEBHOOK_AMQP_ENABLE_PUBLISHER_CONFIRM"
const val amqpPublisherConfirmTimeout = "WEBHOOK_AMQP_PUBLISHER_CONFIRM_TIMEOUT"

const val syslogProtocol = "WEBHOOK_SYSLOG_PROTOCOL"
const val syslogHostname = "WEBHOOK_SYSLOG_HOSTNAME"
const val syslogAppName = "WEBHOOK_SYSLOG_APP_NAME"
const val syslogFacility = "WEBHOOK_SYSLOG_FACILITY"
const val syslogSeverity = "WEBHOOK_SYSLOG_SEVERITY"
const val syslogServerHostname = "WEBHOOK_SYSLOG_SERVER_HOSTNAME"
const val syslogServerPort = "WEBHOOK_SYSLOG_SERVER_PORT"
const val syslogMessageFormat = "WEBHOOK_SYSLOG_MESSAGE_FORMAT"

private fun getConfig(key: String): String? = System.getenv(key) ?: System.getProperty(key)

fun String.cf() = getConfig(this)
fun String.bf(compare: String = "true") = this.cf() == compare
fun String.cff() = getConfig(this)!!
fun String.cfe(defaultValue: () -> String) = getConfig(this).orEmpty().ifEmpty(defaultValue)