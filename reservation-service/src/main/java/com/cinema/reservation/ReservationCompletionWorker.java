package com.cinema.reservation;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "reservation.completion.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationCompletionWorker {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationCompletionWorker.class);

    private final JdbcTemplate jdbc;
    private final ReservationCompletionService completion;
    private final ReservationService reservations;

    public ReservationCompletionWorker(
            JdbcTemplate jdbc,
            ReservationCompletionService completion,
            ReservationService reservations) {
        this.jdbc = jdbc;
        this.completion = completion;
        this.reservations = reservations;
    }

    @Scheduled(
            fixedDelayString = "${reservation.completion.interval-ms:5000}",
            initialDelayString = "${reservation.completion.initial-delay-ms:10000}"
    )
    public void processPendingReservations() {
        var pending = jdbc.query("""
                SELECT id, status
                FROM reservations
                WHERE status IN ('CONFIRMING', 'CANCEL_PENDING')
                ORDER BY updated_at, id
                """,
                (result, row) -> new PendingReservation(
                        result.getObject("id", UUID.class),
                        result.getString("status")
                )
        );

        for (var reservation : pending) {
            try {
                if ("CONFIRMING".equals(reservation.status())) {
                    completion.complete(reservation.id());
                } else {
                    reservations.cancel(reservation.id());
                }
            } catch (Exception exception) {
                log.warn(
                        "Obrada rezervacije {} nije završena; "
                                + "pokušaćemo ponovo. Razlog: {}",
                        reservation.id(),
                        exception.getMessage()
                );
            }
        }
    }

    private record PendingReservation(UUID id, String status) {
    }
}