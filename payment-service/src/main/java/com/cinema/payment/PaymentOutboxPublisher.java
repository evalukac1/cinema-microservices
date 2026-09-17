package com.cinema.payment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
        name = "payment.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PaymentOutboxPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentOutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;

    public PaymentOutboxPublisher(
            JdbcTemplate jdbc,
            RabbitTemplate rabbit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.interval-ms:2000}")
    public void publishPending() {
        for (int i = 0; i < 20; i++) {
            Boolean published = transaction.execute(status -> {
                var events = jdbc.query("""
                        SELECT id, routing_key, payload
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """,
                        (result, row) -> new OutboxEvent(
                                result.getObject("id", UUID.class),
                                result.getString("routing_key"),
                                result.getString("payload")
                        )
                );

                if (events.isEmpty()) {
                    return false;
                }

                var event = events.getFirst();

                jdbc.update("""
                        UPDATE outbox_events
                        SET attempts = attempts + 1
                        WHERE id = ?
                        """, event.id());

                try {
                    var properties = new MessageProperties();
                    properties.setContentType("application/json");
                    properties.setContentEncoding("UTF-8");
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    properties.setMessageId(event.id().toString());

                    var message = new Message(
                            event.payload().getBytes(StandardCharsets.UTF_8),
                            properties
                    );

                    // Svaki pokušaj slanja ima svoj correlation ID.
                    var correlation = new CorrelationData(
                            UUID.randomUUID().toString()
                    );

                    rabbit.send(
                            RabbitConfiguration.EXCHANGE,
                            event.routingKey(),
                            message,
                            correlation
                    );

                    var confirmation = correlation.getFuture()
                            .get(5, TimeUnit.SECONDS);

                    if (!confirmation.isAck()
                            || correlation.getReturned() != null) {
                        log.warn(
                                "Broker nije prihvatio ili rutirao događaj {}",
                                event.id()
                        );
                        return false;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception exception) {
                    log.warn(
                            "Slanje događaja {} nije uspelo: {}",
                            event.id(),
                            exception.getMessage()
                    );
                    return false;
                }

                jdbc.update("""
                        UPDATE outbox_events
                        SET published_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, event.id());

                return true;
            });

            if (!Boolean.TRUE.equals(published)) {
                break;
            }
        }
    }

    private record OutboxEvent(
            UUID id,
            String routingKey,
            String payload
    ) {
    }
}