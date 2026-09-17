package com.cinema.reservation;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfiguration {

    public static final String EXCHANGE = "cinema.events";
    public static final String REQUEST_QUEUE = "payment.requests";
    public static final String RESULT_QUEUE = "reservation.payment-results";

    @Bean
    Declarables paymentTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var deadExchange = new DirectExchange("cinema.dead", true, false);

        var requests = QueueBuilder.durable(REQUEST_QUEUE)
                .deadLetterExchange("cinema.dead")
                .deadLetterRoutingKey("payment.requests.failed")
                .build();

        var results = QueueBuilder.durable(RESULT_QUEUE)
                .deadLetterExchange("cinema.dead")
                .deadLetterRoutingKey("reservation.payment-results.failed")
                .build();

        var failedRequests = QueueBuilder
                .durable("payment.requests.dlq").build();

        var failedResults = QueueBuilder
                .durable("reservation.payment-results.dlq").build();

        return new Declarables(
                exchange,
                deadExchange,
                requests,
                results,
                failedRequests,
                failedResults,
                BindingBuilder.bind(requests).to(exchange)
                        .with("payment.requested"),
                BindingBuilder.bind(results).to(exchange)
                        .with("payment.result"),
                BindingBuilder.bind(failedRequests).to(deadExchange)
                        .with("payment.requests.failed"),
                BindingBuilder.bind(failedResults).to(deadExchange)
                        .with("reservation.payment-results.failed")
        );
    }
}