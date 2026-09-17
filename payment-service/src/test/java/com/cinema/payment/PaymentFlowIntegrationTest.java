package com.cinema.payment;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cinema.payment.PaymentMessages.PaymentRequested;
import com.cinema.payment.PaymentMessages.PaymentResponse;
import com.cinema.payment.RefundMessages.RefundRequested;

@SpringBootTest(properties = {
        "payment.outbox.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@Import(TestcontainersConfiguration.class)
class PaymentFlowIntegrationTest {

    @Autowired
    PaymentService payments;

    @Autowired
    RefundService refunds;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void shouldCreateSuccessfulPaymentAndOutboxEvent() {
        var request = paymentRequest(false, Instant.now().plusSeconds(600));

        payments.process(request);

        var payment = payments.getByReservation(request.reservationId());

        assertEquals("SUCCEEDED", payment.status());
        assertNull(payment.failureReason());
        assertEquals(0, new BigDecimal("17.00").compareTo(payment.amount()));
        assertEquals("EUR", payment.currency());
        assertEquals(1L, eventCount(payment.id(), "PaymentResult"));
        assertEquals(1L, processedCount(request.eventId()));

        Long unpublished = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                  AND event_type = 'PaymentResult'
                  AND published_at IS NULL
                """, Long.class, payment.id());

        assertEquals(Long.valueOf(1), unpublished);
    }

    @Test
    void shouldDeclinePaymentWhenRequested() {
        var request = paymentRequest(true, Instant.now().plusSeconds(600));

        payments.process(request);

        var payment = payments.getByReservation(request.reservationId());

        assertEquals("FAILED", payment.status());
        assertEquals("SIMULATED_DECLINE", payment.failureReason());
        assertEquals(1L, eventCount(payment.id(), "PaymentResult"));
    }

    @Test
    void shouldRejectPaymentAfterExpiration() {
        var request = paymentRequest(false, Instant.now().minusSeconds(60));

        payments.process(request);

        var payment = payments.getByReservation(request.reservationId());

        assertEquals("FAILED", payment.status());
        assertEquals("RESERVATION_EXPIRED", payment.failureReason());
        assertEquals(1L, eventCount(payment.id(), "PaymentResult"));
    }

    @Test
    void shouldNotChargeAgainForRepeatedRequests() {
        var request = paymentRequest(false, Instant.now().plusSeconds(600));

        payments.process(request);
        var original = payments.getByReservation(request.reservationId());

        // Ponovna isporuka iste poruke.
        payments.process(request);

        // Novi događaj za isto poslovno plaćanje.
        payments.process(new PaymentRequested(
                UUID.randomUUID(),
                request.reservationId(),
                request.amount(),
                request.currency(),
                request.expiresAt(),
                request.simulateFailure()
        ));

        var repeated = payments.getByReservation(request.reservationId());

        assertEquals(original.id(), repeated.id());
        assertEquals("SUCCEEDED", repeated.status());

        Long paymentCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM payments WHERE reservation_id = ?
                """, Long.class, request.reservationId());

        assertEquals(Long.valueOf(1), paymentCount);
        assertEquals(1L, eventCount(original.id(), "PaymentResult"));
    }

    @Test
    void shouldRefundOnlyOnce() {
        var request = paymentRequest(false, Instant.now().plusSeconds(600));
        payments.process(request);

        var payment = payments.getByReservation(request.reservationId());
        var refund = refundRequest(payment, payment.amount());

        refunds.process(refund);
        refunds.process(refund);

        refunds.process(new RefundRequested(
                UUID.randomUUID(),
                payment.id(),
                payment.reservationId(),
                payment.amount(),
                payment.currency()
        ));

        var result = payments.getByReservation(request.reservationId());

        assertEquals("REFUNDED", result.status());
        assertEquals(1L, eventCount(payment.id(), "RefundResult"));
        assertEquals(1L, eventCount(payment.id(), "PaymentResult"));
    }

    @Test
    void shouldRejectWrongRefundAmountWithoutChangingPayment() {
        var request = paymentRequest(false, Instant.now().plusSeconds(600));
        payments.process(request);

        var payment = payments.getByReservation(request.reservationId());
        var refund = refundRequest(payment, new BigDecimal("18.00"));

        assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> refunds.process(refund)
        );

        assertEquals(
                "SUCCEEDED",
                payments.getByReservation(request.reservationId()).status()
        );
        assertEquals(0L, eventCount(payment.id(), "RefundResult"));
        assertEquals(0L, processedCount(refund.eventId()));
    }

    @Test
    void shouldNotRefundFailedPayment() {
        var request = paymentRequest(true, Instant.now().plusSeconds(600));
        payments.process(request);

        var payment = payments.getByReservation(request.reservationId());
        var refund = refundRequest(payment, payment.amount());

        assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> refunds.process(refund)
        );

        assertEquals(
                "FAILED",
                payments.getByReservation(request.reservationId()).status()
        );
        assertEquals(0L, eventCount(payment.id(), "RefundResult"));
        assertEquals(0L, processedCount(refund.eventId()));
    }

    private PaymentRequested paymentRequest(
            boolean simulateFailure,
            Instant expiresAt) {
        return new PaymentRequested(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("17.00"),
                "EUR",
                expiresAt,
                simulateFailure
        );
    }

    private RefundRequested refundRequest(
            PaymentResponse payment,
            BigDecimal amount) {
        return new RefundRequested(
                UUID.randomUUID(),
                payment.id(),
                payment.reservationId(),
                amount,
                payment.currency()
        );
    }

    private long eventCount(UUID paymentId, String eventType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ? AND event_type = ?
                """, Long.class, paymentId, eventType);
    }

    private long processedCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM processed_events WHERE event_id = ?
                """, Long.class, eventId);
    }
}