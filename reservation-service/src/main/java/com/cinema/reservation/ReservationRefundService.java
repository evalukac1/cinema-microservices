package com.cinema.reservation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.reservation.RefundMessages.RefundRequested;
import com.cinema.reservation.RefundMessages.RefundResult;

@Service
public class ReservationRefundService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ReservationRefundService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void requestRefund(UUID reservationId) {
        var reservation = lockReservation(reservationId);

        if (!"REFUND_PENDING".equals(reservation.status())) {
            return;
        }

        if (reservation.paymentId() == null) {
            throw new IllegalStateException(
                    "Rezervacija nema evidentirano plaćanje."
            );
        }

        boolean alreadyRequested = Boolean.TRUE.equals(
                jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM outbox_events
                            WHERE aggregate_id = ?
                              AND event_type = 'RefundRequested'
                        )
                        """,
                        Boolean.class,
                        reservationId
                )
        );

        if (alreadyRequested) {
            return;
        }

        UUID eventId = UUID.randomUUID();

        var event = new RefundRequested(
                eventId,
                reservation.paymentId(),
                reservationId,
                reservation.amount(),
                reservation.currency()
        );

        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_id, event_type, routing_key,
                     payload, created_at)
                VALUES (?, ?, 'RefundRequested', 'refund.requested',
                        ?, CURRENT_TIMESTAMP)
                """,
                eventId,
                reservationId,
                json.writeValueAsString(event)
        );
    }

    @Transactional
    public void processResult(RefundResult event) {
        var reservation = lockReservation(event.reservationId());

        if (!event.paymentId().equals(reservation.paymentId())
                || event.amount().compareTo(reservation.amount()) != 0
                || !event.currency().equals(reservation.currency())
                || !"REFUNDED".equals(event.status())) {
            throw reject(
                    "Rezultat povraćaja ne odgovara rezervaciji."
            );
        }

        if (!"REFUND_PENDING".equals(reservation.status())
                && !"REFUNDED".equals(reservation.status())) {
            throw reject(
                    "Rezervacija nije u statusu za povraćaj novca."
            );
        }

        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, processed_at)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId());

        if (inserted == 0 || "REFUNDED".equals(reservation.status())) {
            return;
        }

        jdbc.update("""
                UPDATE reservations
                SET status = 'REFUNDED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, event.reservationId());
    }

    private RefundState lockReservation(UUID reservationId) {
        var reservations = jdbc.query("""
                SELECT payment_id, status, total_price, currency
                FROM reservations
                WHERE id = ?
                FOR UPDATE
                """,
                (result, row) -> new RefundState(
                        result.getObject("payment_id", UUID.class),
                        result.getString("status"),
                        result.getBigDecimal("total_price"),
                        result.getString("currency")
                ),
                reservationId
        );

        if (reservations.isEmpty()) {
            throw reject("Rezervacija za povraćaj ne postoji.");
        }

        return reservations.getFirst();
    }

    private AmqpRejectAndDontRequeueException reject(String message) {
        return new AmqpRejectAndDontRequeueException(message);
    }

    private record RefundState(
            UUID paymentId,
            String status,
            BigDecimal amount,
            String currency
    ) {
    }
}