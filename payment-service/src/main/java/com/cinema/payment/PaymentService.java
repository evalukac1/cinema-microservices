package com.cinema.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.payment.PaymentMessages.*;

@Service
public class PaymentService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public PaymentService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void process(PaymentRequested request) {
        // Zahteve za istu rezervaciju obrađujemo redom.
        jdbc.query("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(? AS text), 0)
                )
                """,
                (result, row) -> 0,
                request.reservationId().toString()
        );

        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, processed_at)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, request.eventId());

        if (inserted == 0) {
            return;
        }

        var previousPayments = jdbc.query("""
                SELECT id, reservation_id, amount, currency,
                       status, failure_reason
                FROM payments
                WHERE reservation_id = ?
                """,
                (result, row) -> new PaymentResponse(
                        result.getObject("id", UUID.class),
                        result.getObject("reservation_id", UUID.class),
                        result.getBigDecimal("amount"),
                        result.getString("currency"),
                        result.getString("status"),
                        result.getString("failure_reason")
                ),
                request.reservationId()
        );

        if (!previousPayments.isEmpty()) {
            var previous = previousPayments.getFirst();

            if (previous.amount().compareTo(request.amount()) != 0
                    || !previous.currency().equals(request.currency())) {
                throw new AmqpRejectAndDontRequeueException(
                        "Ponovljen zahtev sadrži drugačiji iznos ili valutu."
                );
            }

            // Za ovu rezervaciju već postoji obrađeno plaćanje.
            return;
        }

        String failureReason = null;

        if (!request.expiresAt().isAfter(Instant.now())) {
            failureReason = "RESERVATION_EXPIRED";
        } else if (request.simulateFailure()) {
            failureReason = "SIMULATED_DECLINE";
        }

        String status = failureReason == null ? "SUCCEEDED" : "FAILED";
        UUID paymentId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO payments
                    (id, reservation_id, amount, currency, status,
                     failure_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                paymentId,
                request.reservationId(),
                request.amount(),
                request.currency(),
                status,
                failureReason
        );

        UUID eventId = UUID.randomUUID();

        var event = new PaymentResult(
                eventId,
                paymentId,
                request.reservationId(),
                request.amount(),
                request.currency(),
                status,
                failureReason
        );

        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_id, event_type, routing_key,
                     payload, created_at)
                VALUES (?, ?, 'PaymentResult', 'payment.result',
                        ?, CURRENT_TIMESTAMP)
                """,
                eventId,
                paymentId,
                json.writeValueAsString(event)
        );
    }

    public PaymentResponse getByReservation(UUID reservationId) {
        var payments = jdbc.query("""
                SELECT id, reservation_id, amount, currency,
                       status, failure_reason
                FROM payments
                WHERE reservation_id = ?
                """,
                (result, row) -> new PaymentResponse(
                        result.getObject("id", UUID.class),
                        result.getObject("reservation_id", UUID.class),
                        result.getBigDecimal("amount"),
                        result.getString("currency"),
                        result.getString("status"),
                        result.getString("failure_reason")
                ),
                reservationId
        );

        if (payments.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Plaćanje još nije evidentirano."
            );
        }

        return payments.getFirst();
    }
}