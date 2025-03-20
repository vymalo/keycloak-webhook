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

const val natsServerUrlKey = "WEBHOOK_NATS_SERVER_URL"
const val natsSubjectKey = "WEBHOOK_NATS_SUBJECT"
const val natsUsernameKey = "WEBHOOK_NATS_USERNAME"
const val natsPasswordKey = "WEBHOOK_NATS_PASSWORD"
const val natsTokenKey = "WEBHOOK_NATS_TOKEN"
const val natsCredentialsKey = "WEBHOOK_NATS_CREDENTIALS"
const val natsSsl = "WEBHOOK_NATS_SSL"

private fun getConfig(key: String): String? = System.getenv(key) ?: System.getProperties().getProperty(key)

fun String.cf() = getConfig(this)
fun String.bf(compare: String = "true") = this.cf() == compare
fun String.cff() = getConfig(this)!!