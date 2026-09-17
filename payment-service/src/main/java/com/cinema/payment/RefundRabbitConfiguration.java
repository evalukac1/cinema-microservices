package com.cinema.payment;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RefundRabbitConfiguration {

    public static final String REQUEST_QUEUE = "payment.refund-requests";
    public static final String RESULT_QUEUE = "reservation.refund-results";

    @Bean
    Declarables refundTopology() {
        var exchange = new DirectExchange(
                RabbitConfiguration.EXCHANGE, true, false
        );

        var deadExchange = new DirectExchange(
                "cinema.dead", true, false
        );

        var requests = QueueBuilder.durable(REQUEST_QUEUE)
                .deadLetterExchange("cinema.dead")
                .deadLetterRoutingKey("payment.refund-requests.failed")
                .build();

        var results = QueueBuilder.durable(RESULT_QUEUE)
                .deadLetterExchange("cinema.dead")
                .deadLetterRoutingKey("reservation.refund-results.failed")
                .build();

        var failedRequests = QueueBuilder
                .durable("payment.refund-requests.dlq")
                .build();

        var failedResults = QueueBuilder
                .durable("reservation.refund-results.dlq")
                .build();

        return new Declarables(
                exchange,
                deadExchange,
                requests,
                results,
                failedRequests,
                failedResults,
                BindingBuilder.bind(requests).to(exchange)
                        .with("refund.requested"),
                BindingBuilder.bind(results).to(exchange)
                        .with("refund.result"),
                BindingBuilder.bind(failedRequests).to(deadExchange)
                        .with("payment.refund-requests.failed"),
                BindingBuilder.bind(failedResults).to(deadExchange)
                        .with("reservation.refund-results.failed")
        );
    }
}