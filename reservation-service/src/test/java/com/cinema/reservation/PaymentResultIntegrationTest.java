package com.cinema.reservation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cinema.reservation.ReservationModels.PaymentResult;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PaymentResultIntegrationTest {

    @Autowired
    PaymentResultService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void shouldAcceptSuccessfulPayment() {
        UUID id = createReservation();
        var event = event(id, UUID.randomUUID(), "SUCCEEDED", "17.00");

        service.process(event);

        assertEquals("CONFIRMING", status(id));
        assertEquals(event.paymentId(), paymentId(id));
        assertEquals(1L, processedCount(event.eventId()));
    }

    @Test
    void shouldPrepareCancellationAfterFailedPayment() {
        UUID id = createReservation();
        var event = event(id, UUID.randomUUID(), "FAILED", "17.00");

        service.process(event);

        assertEquals("CANCEL_PENDING", status(id));
        assertEquals(event.paymentId(), paymentId(id));
    }

    @Test
    void shouldNotRevertConfirmedReservationOnRepeatedResult() {
        UUID id = createReservation();
        var original = event(
                id, UUID.randomUUID(), "SUCCEEDED", "17.00"
        );

        service.process(original);

        jdbc.update("""
                UPDATE reservations SET status = 'CONFIRMED' WHERE id = ?
                """, id);

        // Ponovna isporuka identične poruke.
        service.process(original);

        // Isti poslovni rezultat sa novim ID-em događaja.
        var repeated = event(
                id, original.paymentId(), "SUCCEEDED", "17.00"
        );
        service.process(repeated);

        assertEquals("CONFIRMED", status(id));
        assertEquals(original.paymentId(), paymentId(id));
        assertEquals(1L, processedCount(original.eventId()));
        assertEquals(1L, processedCount(repeated.eventId()));
    }

    @Test
    void shouldRejectIncorrectAmountWithoutChangingReservation() {
        UUID id = createReservation();
        var event = event(id, UUID.randomUUID(), "SUCCEEDED", "18.00");

        assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> service.process(event)
        );

        assertEquals("PAYMENT_PENDING", status(id));
        assertNull(paymentId(id));
        assertEquals(0L, processedCount(event.eventId()));
    }

    @Test
    void shouldRejectResultForUnknownReservation() {
        var event = event(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SUCCEEDED",
                "17.00"
        );

        assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> service.process(event)
        );

        assertEquals(0L, processedCount(event.eventId()));
    }

    private UUID createReservation() {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO reservations
                    (id, screening_id, customer_email, status,
                     unit_price, total_price, currency, seat_count,
                     created_at, updated_at, expires_at)
                VALUES (?, 1, 'test@example.com', 'PAYMENT_PENDING',
                        8.50, 17.00, 'EUR', 2,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """, id);

        jdbc.update("""
                INSERT INTO reservation_seats
                    (reservation_id, row_number, seat_number)
                VALUES (?, 1, 1), (?, 1, 2)
                """, id, id);

        return id;
    }

    private PaymentResult event(
            UUID reservationId,
            UUID paymentId,
            String status,
            String amount) {
        return new PaymentResult(
                UUID.randomUUID(),
                paymentId,
                reservationId,
                new BigDecimal(amount),
                "EUR",
                status,
                "FAILED".equals(status) ? "SIMULATED_DECLINE" : null
        );
    }

    private String status(UUID id) {
        return jdbc.queryForObject("""
                SELECT status FROM reservations WHERE id = ?
                """, String.class, id);
    }

    private UUID paymentId(UUID id) {
        return jdbc.queryForObject("""
                SELECT payment_id FROM reservations WHERE id = ?
                """, UUID.class, id);
    }

    private long processedCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM processed_events WHERE event_id = ?
                """, Long.class, eventId);
    }
}