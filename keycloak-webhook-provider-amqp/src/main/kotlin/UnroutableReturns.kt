package com.vymalo.keycloak.webhook

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ReturnListener
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Without the mandatory flag, RabbitMQ quietly drops a message that no queue is bound to receive,
 * and even confirms it: publisher confirms alone can't tell "delivered" from "went nowhere".
 * With WEBHOOK_AMQP_MANDATORY on, the broker hands such messages back instead, and this logs and
 * counts each one.
 *
 * They are not retried: routing only changes when someone binds a queue, which doesn't happen
 * within the milliseconds a retry would take. To keep them, give the exchange an alternate
 * exchange on the broker.
 */
internal class UnroutableReturns(val count: AtomicLong = AtomicLong()) : ReturnListener {

    override fun handleReturn(
        replyCode: Int,
        replyText: String?,
        exchange: String?,
        routingKey: String?,
        properties: AMQP.BasicProperties?,
        body: ByteArray?,
    ) {
        count.incrementAndGet()
        logger.error(
            "Broker returned an undeliverable message: no queue bound to exchange '{}' takes routing key '{}' " +
                "({} {}). It was not delivered.",
            exchange, routingKey, replyCode, replyText,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(UnroutableReturns::class.java)
    }
}
