package com.cinema.reservation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationCompletionService {

    private final JdbcTemplate jdbc;
    private final ReservationClients clients;
    private final TransactionTemplate transaction;

    public ReservationCompletionService(
            JdbcTemplate jdbc,
            ReservationClients clients,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void complete(UUID reservationId) {
        transaction.executeWithoutResult(transactionStatus -> {
            var reservations = jdbc.query("""
                    SELECT screening_id, status, payment_id
                    FROM reservations
                    WHERE id = ?
                    FOR UPDATE
                    """,
                    (result, row) -> new CompletionState(
                            result.getLong("screening_id"),
                            result.getString("status"),
                            result.getObject("payment_id", UUID.class)
                    ),
                    reservationId
            );

            if (reservations.isEmpty()) {
                return;
            }

            var reservation = reservations.getFirst();

            if (!"CONFIRMING".equals(reservation.status())) {
                return;
            }

            if (reservation.paymentId() == null) {
                throw new IllegalStateException(
                        "Rezervacija nema evidentirano plaćanje."
                );
            }

            var hold = clients.confirmHold(reservationId);

            if (hold == null
                    || !reservationId.equals(hold.reservationId())
                    || !reservation.screeningId().equals(hold.screeningId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Servis sedišta je vratio neispravno zauzimanje."
                );
            }

            String nextStatus;

            if ("CONFIRMED".equals(hold.status())) {
                nextStatus = "CONFIRMED";
            } else if ("EXPIRED".equals(hold.status())
                    || "RELEASED".equals(hold.status())) {
                nextStatus = "REFUND_PENDING";
            } else {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Potvrda sedišta još nije završena."
                );
            }

            jdbc.update("""
                    UPDATE reservations
                    SET status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    nextStatus,
                    reservationId
            );
        });
    }

    private record CompletionState(
            Long screeningId,
            String status,
            UUID paymentId
    ) {
    }
}