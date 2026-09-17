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
        name = "reservation.refund.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationRefundWorker {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationRefundWorker.class);

    private final JdbcTemplate jdbc;
    private final ReservationRefundService service;

    public ReservationRefundWorker(
            JdbcTemplate jdbc,
            ReservationRefundService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${reservation.refund.interval-ms:5000}",
            initialDelayString = "${reservation.refund.initial-delay-ms:10000}"
    )
    public void requestPendingRefunds() {
        var ids = jdbc.query("""
                SELECT r.id
                FROM reservations r
                WHERE r.status = 'REFUND_PENDING'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM outbox_events o
                      WHERE o.aggregate_id = r.id
                        AND o.event_type = 'RefundRequested'
                  )
                ORDER BY r.updated_at, r.id
                LIMIT 50
                """,
                (result, row) -> result.getObject("id", UUID.class)
        );

        for (UUID id : ids) {
            try {
                service.requestRefund(id);
            } catch (Exception exception) {
                log.warn(
                        "Zahtev za povraćaj rezervacije {} nije sačuvan; "
                                + "pokušaćemo ponovo. Razlog: {}",
                        id,
                        exception.getMessage()
                );
            }
        }
    }
}