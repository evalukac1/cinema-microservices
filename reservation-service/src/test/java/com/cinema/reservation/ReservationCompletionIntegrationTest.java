package com.cinema.reservation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.reservation.ReservationModels.HoldResponse;
import com.cinema.reservation.RefundMessages.RefundResult;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReservationCompletionIntegrationTest {

    @Autowired
    ReservationCompletionService completion;

    @Autowired
    ReservationRefundService refunds;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ReservationClients clients;

    @Test
    void shouldConfirmReservationOnlyOnce() {
        var reservation = createReservation("CONFIRMING");

        when(clients.confirmHold(reservation.id())).thenReturn(
                hold(reservation.id(), "CONFIRMED")
        );

        completion.complete(reservation.id());
        completion.complete(reservation.id());

        assertEquals("CONFIRMED", status(reservation.id()));
        verify(clients, times(1)).confirmHold(reservation.id());
    }

    @Test
    void shouldRequestRefundWhenHoldExpired() {
        var reservation = createReservation("CONFIRMING");

        when(clients.confirmHold(reservation.id())).thenReturn(
                hold(reservation.id(), "EXPIRED")
        );

        completion.complete(reservation.id());

        assertEquals("REFUND_PENDING", status(reservation.id()));
    }

    @Test
    void shouldRetryAfterInventoryFailure() {
        var reservation = createReservation("CONFIRMING");
        var confirmed = hold(reservation.id(), "CONFIRMED");

        when(clients.confirmHold(reservation.id()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Inventory nije dostupan."
                ))
                .thenReturn(confirmed);

        assertThrows(
                ResponseStatusException.class,
                () -> completion.complete(reservation.id())
        );

        assertEquals("CONFIRMING", status(reservation.id()));

        completion.complete(reservation.id());

        assertEquals("CONFIRMED", status(reservation.id()));
    }

    @Test
    void shouldRejectHoldForAnotherScreening() {
        var reservation = createReservation("CONFIRMING");

        when(clients.confirmHold(reservation.id())).thenReturn(
                new HoldResponse(
                        reservation.id(),
                        999L,
                        "CONFIRMED",
                        Instant.now().plusSeconds(600)
                )
        );

        assertThrows(
                ResponseStatusException.class,
                () -> completion.complete(reservation.id())
        );

        assertEquals("CONFIRMING", status(reservation.id()));
    }

    @Test
    void shouldCreateOnlyOneRefundRequest() {
        var reservation = createReservation("REFUND_PENDING");

        refunds.requestRefund(reservation.id());
        refunds.requestRefund(reservation.id());

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                  AND event_type = 'RefundRequested'
                  AND routing_key = 'refund.requested'
                  AND published_at IS NULL
                """, Long.class, reservation.id());

        assertEquals(Long.valueOf(1), count);
        assertEquals("REFUND_PENDING", status(reservation.id()));
    }

    @Test
    void shouldAcceptRepeatedRefundResult() {
        var reservation = createReservation("REFUND_PENDING");
        refunds.requestRefund(reservation.id());

        var event = refundResult(
                reservation, new BigDecimal("17.00")
        );

        refunds.processResult(event);
        refunds.processResult(event);

        assertEquals("REFUNDED", status(reservation.id()));

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE event_id = ?
                """, Long.class, event.eventId());

        assertEquals(Long.valueOf(1), count);
    }

    @Test
    void shouldRejectIncorrectRefundAmount() {
        var reservation = createReservation("REFUND_PENDING");
        var event = refundResult(
                reservation, new BigDecimal("18.00")
        );

        assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> refunds.processResult(event)
        );

        assertEquals("REFUND_PENDING", status(reservation.id()));

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE event_id = ?
                """, Long.class, event.eventId());

        assertEquals(Long.valueOf(0), count);
    }

    private TestReservation createReservation(String status) {
        UUID id = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO reservations
                    (id, screening_id, customer_email, status,
                     unit_price, total_price, currency, seat_count,
                     created_at, updated_at, expires_at, payment_id)
                VALUES (?, 1, 'test@example.com', ?,
                        8.50, 17.00, 'EUR', 2,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes', ?)
                """, id, status, paymentId);

        jdbc.update("""
                INSERT INTO reservation_seats
                    (reservation_id, row_number, seat_number)
                VALUES (?, 1, 1), (?, 1, 2)
                """, id, id);

        return new TestReservation(id, paymentId);
    }

    private HoldResponse hold(UUID id, String status) {
        return new HoldResponse(
                id,
                1L,
                status,
                Instant.now().plusSeconds(600)
        );
    }

    private RefundResult refundResult(
            TestReservation reservation,
            BigDecimal amount) {
        return new RefundResult(
                UUID.randomUUID(),
                reservation.paymentId(),
                reservation.id(),
                amount,
                "EUR",
                "REFUNDED"
        );
    }

    private String status(UUID id) {
        return jdbc.queryForObject("""
                SELECT status FROM reservations WHERE id = ?
                """, String.class, id);
    }

    private record TestReservation(UUID id, UUID paymentId) {
    }
}