package com.vymalo.keycloak.webhook.model

class ClientConfig(
    var amqp: Amqp? = null,
    var http: Http? = null,
    var takeList: Set<String>? = null,
) {
    companion object {
        class Amqp(
            val username: String,
            val password: String,
            val host: String,
            val port: String,
            val vHost: String?,
            val ssl: Boolean,
            val exchange: String,
        )

        class Http(
            val username: String?,
            val password: String?,
            val baseUrl: String,
        )
    }
}