package com.cinema.reservation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.reservation.ReservationModels.*;

@Service
public class ReservationPaymentService {

    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private final ReservationClients clients;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public ReservationPaymentService(
            JdbcTemplate jdbc,
            ReservationService reservations,
            ReservationClients clients,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.clients = clients;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ReservationResponse requestPayment(
            UUID id, PaymentRequest request) {

        var result = Objects.requireNonNull(transaction.execute(status -> {
            var ids = jdbc.query("""
                    SELECT id FROM reservations
                    WHERE id = ?
                    FOR UPDATE
                    """,
                    (row, index) -> row.getObject("id", UUID.class),
                    id
            );

            if (ids.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Rezervacija ne postoji."
                );
            }

            var current = reservations.getById(id);

            if (current.status().equals("PAYMENT_PENDING")
                    || current.status().equals("CONFIRMING")
                    || current.status().equals("CONFIRMED")) {
                return current;
            }

            if (!current.status().equals("AWAITING_PAYMENT")) {
                throw conflict(
                        "Plaćanje nije dozvoljeno u statusu " + current.status()
                );
            }

            var hold = clients.getHold(id);

            if (!current.screeningId().equals(hold.screeningId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Zauzimanje pripada drugoj projekciji."
                );
            }

            if ("EXPIRED".equals(hold.status())) {
                updateStatus(id, "EXPIRED");
                return reservations.getById(id);
            }

            if ("RELEASED".equals(hold.status())) {
                updateStatus(id, "CANCELLED");
                return reservations.getById(id);
            }

            if (!"HELD".equals(hold.status()) || hold.expiresAt() == null) {
                throw conflict("Sedišta nisu privremeno zauzeta.");
            }

            if (!hold.expiresAt().isAfter(Instant.now())) {
                updateStatus(id, "EXPIRED");
                return reservations.getById(id);
            }

            UUID eventId = UUID.randomUUID();

            var event = new PaymentRequested(
                    eventId,
                    current.id(),
                    current.totalPrice(),
                    current.currency(),
                    hold.expiresAt(),
                    request.simulateFailure()
            );

            jdbc.update("""
                    INSERT INTO outbox_events
                        (id, aggregate_id, event_type, routing_key,
                         payload, created_at)
                    VALUES (?, ?, 'PaymentRequested', 'payment.requested',
                            ?, CURRENT_TIMESTAMP)
                    """,
                    eventId,
                    id,
                    json.writeValueAsString(event)
            );

            jdbc.update("""
                    UPDATE reservations
                    SET status = 'PAYMENT_PENDING',
                        expires_at = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    Timestamp.from(hold.expiresAt()),
                    id
            );

            return reservations.getById(id);
        }));

        if (result.status().equals("EXPIRED")
                || result.status().equals("CANCELLED")) {
            throw conflict("Zauzimanje više nije aktivno.");
        }

        return result;
    }

    private void updateStatus(UUID id, String status) {
        jdbc.update("""
                UPDATE reservations
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, id);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}