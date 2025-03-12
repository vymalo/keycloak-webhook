package com.vymalo.keycloak.webhook

import com.google.gson.Gson
import com.vymalo.keycloak.webhook.models.NatsConfig
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import org.keycloak.utils.MediaType
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Duration

class NatsWebhookHandler : WebhookHandler {
    private lateinit var connection: Connection
    private lateinit var subject: String

    companion object {
        const val PROVIDER_ID = "webhook-nats"
        
        @JvmStatic
        private val gson = Gson()

        @JvmStatic
        private val logger = LoggerFactory.getLogger(NatsWebhookHandler::class.java)
    }

    override fun sendWebhook(request: WebhookPayload) {
        var attempt = 0
        while (attempt < 3) {
            try {
                val requestStr = gson.toJson(request)
                val message = io.nats.client.impl.NatsMessage.builder()
                    .subject(subject)
                    .data(requestStr.toByteArray(StandardCharsets.UTF_8))
                    .build()
                
                connection.publish(message)
                logger.debug("Webhook message sent to NATS: {}", request)
                break // Exit loop if successful
            } catch (ex: Exception) {
                attempt++
                if (attempt >= 3) {
                    logger.error("Failed to send webhook message to NATS after $attempt attempts", ex)
                } else {
                    logger.warn("Attempt $attempt to send webhook message to NATS failed: ${ex.message}", ex)
                    Thread.sleep(1000L) // Wait before retrying
                }
            }
        }
    }

    override fun getId(): String = PROVIDER_ID

    override fun close() {
        runCatching {
            if (connection.status == Connection.Status.CONNECTED) {
                connection.close()
            }
        }.onFailure { logger.warn("Error closing NATS connection", it) }
    }

    override fun initHandler() {
        val nats = NatsConfig.fromEnv()
        subject = nats.subject

        val optionsBuilder = Options.Builder()
            .server(nats.serverUrl)
            .connectionTimeout(Duration.ofSeconds(5))
            .reconnectWait(Duration.ofSeconds(1))
            .maxReconnects(-1) // Unlimited reconnects
            .pingInterval(Duration.ofSeconds(30))

        // Configure authentication
        when {
            nats.username != null && nats.password != null -> {
                optionsBuilder.userInfo(nats.username, nats.password)
            }
            nats.token != null -> {
                optionsBuilder.token(nats.token.toCharArray())
            }
            nats.credentials != null -> {
                val authHandler = Nats.credentials(nats.credentials)
                optionsBuilder.authHandler(authHandler)
            }
        }

        // Configure SSL if enabled
        if (nats.ssl) {
            optionsBuilder.secure()
        }

        connection = Nats.connect(optionsBuilder.build())
        logger.info("Connected to NATS server at {}", nats.serverUrl)
    }
}