package com.cinema.reservation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cinema.reservation.ReservationModels.PaymentResult;

@Service
public class PaymentResultService {

    private final JdbcTemplate jdbc;

    public PaymentResultService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void process(PaymentResult event) {
        var reservations = jdbc.query("""
                SELECT status, total_price, currency, payment_id
                FROM reservations
                WHERE id = ?
                FOR UPDATE
                """,
                (result, row) -> new ReservationState(
                        result.getString("status"),
                        result.getBigDecimal("total_price"),
                        result.getString("currency"),
                        result.getObject("payment_id", UUID.class)
                ),
                event.reservationId()
        );

        if (reservations.isEmpty()) {
            throw reject("Rezervacija iz događaja ne postoji.");
        }

        var reservation = reservations.getFirst();

        if (reservation.totalPrice().compareTo(event.amount()) != 0
                || !reservation.currency().equals(event.currency())) {
            throw reject("Iznos ili valuta ne odgovaraju rezervaciji.");
        }

        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, processed_at)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId());

        if (inserted == 0) {
            return;
        }

        if (reservation.paymentId() != null) {
            if (!reservation.paymentId().equals(event.paymentId())) {
                throw reject("Rezervacija već ima drugo plaćanje.");
            }

            boolean previouslySucceeded =
                    reservation.status().equals("CONFIRMING")
                    || reservation.status().equals("CONFIRMED")
                    || reservation.status().equals("REFUND_PENDING")
                    || reservation.status().equals("REFUNDED");

            boolean previouslyFailed =
                    reservation.status().equals("CANCEL_PENDING")
                    || reservation.status().equals("CANCELLED")
                    || reservation.status().equals("EXPIRED");

            if (event.status().equals("SUCCEEDED") && previouslySucceeded) {
                return;
            }

            if (event.status().equals("FAILED") && previouslyFailed) {
                return;
            }

            throw reject("Rezultat plaćanja je u konfliktu sa prethodnim.");
        }

        if (!reservation.status().equals("PAYMENT_PENDING")) {
            throw reject(
                    "Rezervacija ne očekuje rezultat plaćanja: "
                            + reservation.status()
            );
        }

        String nextStatus = event.status().equals("SUCCEEDED")
                ? "CONFIRMING"
                : "CANCEL_PENDING";

        jdbc.update("""
                UPDATE reservations
                SET payment_id = ?,
                    status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                event.paymentId(),
                nextStatus,
                event.reservationId()
        );
    }

    private AmqpRejectAndDontRequeueException reject(String message) {
        return new AmqpRejectAndDontRequeueException(message);
    }

    private record ReservationState(
            String status,
            BigDecimal totalPrice,
            String currency,
            UUID paymentId
    ) {
    }
}