package com.cinema.payment;

import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.payment.PaymentMessages.PaymentResponse;
import com.cinema.payment.RefundMessages.RefundRequested;
import com.cinema.payment.RefundMessages.RefundResult;

@Service
public class RefundService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public RefundService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void process(RefundRequested request) {
        // Isti ključ zaključavanja koristi i obrada plaćanja.
        jdbc.query("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(? AS text), 0)
                )
                """,
                (result, row) -> 0,
                request.reservationId().toString()
        );

        var payments = jdbc.query("""
                SELECT id, reservation_id, amount, currency,
                       status, failure_reason
                FROM payments
                WHERE id = ?
                FOR UPDATE
                """,
                (result, row) -> new PaymentResponse(
                        result.getObject("id", UUID.class),
                        result.getObject("reservation_id", UUID.class),
                        result.getBigDecimal("amount"),
                        result.getString("currency"),
                        result.getString("status"),
                        result.getString("failure_reason")
                ),
                request.paymentId()
        );

        if (payments.isEmpty()) {
            throw reject("Plaćanje za povraćaj ne postoji.");
        }

        var payment = payments.getFirst();

        if (!payment.reservationId().equals(request.reservationId())
                || payment.amount().compareTo(request.amount()) != 0
                || !payment.currency().equals(request.currency())) {
            throw reject(
                    "Zahtev za povraćaj ne odgovara podacima plaćanja."
            );
        }

        if (!"SUCCEEDED".equals(payment.status())
                && !"REFUNDED".equals(payment.status())) {
            throw reject(
                    "Povraćaj je dozvoljen samo za uspešno plaćanje."
            );
        }

        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, processed_at)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, request.eventId());

        if (inserted == 0) {
            return;
        }

        // Ponovljeni zahtev ne izvršava povraćaj drugi put.
        // Prvi rezultat je već sačuvan u outbox tabeli.
        if ("REFUNDED".equals(payment.status())) {
            return;
        }

        jdbc.update("""
                UPDATE payments
                SET status = 'REFUNDED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, payment.id());

        UUID eventId = UUID.randomUUID();

        var event = new RefundResult(
                eventId,
                payment.id(),
                payment.reservationId(),
                payment.amount(),
                payment.currency(),
                "REFUNDED"
        );

        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_id, event_type, routing_key,
                     payload, created_at)
                VALUES (?, ?, 'RefundResult', 'refund.result',
                        ?, CURRENT_TIMESTAMP)
                """,
                eventId,
                payment.id(),
                json.writeValueAsString(event)
        );
    }

    private AmqpRejectAndDontRequeueException reject(String message) {
        return new AmqpRejectAndDontRequeueException(message);
    }
}