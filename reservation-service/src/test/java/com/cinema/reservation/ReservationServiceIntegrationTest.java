package com.cinema.reservation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.reservation.ReservationModels.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationServiceIntegrationTest {

    @Autowired
    ReservationService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ReservationClients clients;

    Instant expiresAt;

    @BeforeEach
    void setUp() {
        expiresAt = Instant.now()
                .plus(10, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MICROS);

        when(clients.getScreening(1L)).thenReturn(
                new ScreeningInfo(
                        1L,
                        Instant.now().plus(2, ChronoUnit.DAYS),
                        new BigDecimal("8.50"),
                        "EUR"
                )
        );

        when(clients.createHold(any(ReservationResponse.class)))
                .thenAnswer(invocation -> {
                    ReservationResponse reservation =
                            invocation.getArgument(0);

                    return new HoldResponse(
                            reservation.id(),
                            reservation.screeningId(),
                            "HELD",
                            expiresAt
                    );
                });
    }

    @Test
    void shouldCalculatePriceAndStoreReservation() {
        var request = request(UUID.randomUUID());

        var result = service.create(request);

        assertEquals("AWAITING_PAYMENT", result.status());
        assertEquals(new BigDecimal("17.00"), result.totalPrice());
        assertEquals(new BigDecimal("8.50"), result.unitPrice());
        assertEquals("EUR", result.currency());
        assertEquals(expiresAt, result.expiresAt());
        assertEquals(2, result.seats().size());
        assertEquals(result, service.getById(request.reservationId()));
    }

    @Test
    void repeatedRequestShouldNotCreateAnotherHold() {
        var request = request(UUID.randomUUID());

        var first = service.create(request);
        var repeated = service.create(request);

        assertEquals(first, repeated);
        verify(clients, times(1))
                .createHold(any(ReservationResponse.class));
    }

    @Test
    void shouldRejectChangedRequestWithSameId() {
        UUID id = UUID.randomUUID();
        service.create(request(id));

        var changed = new CreateReservationRequest(
                id,
                1L,
                "another@example.com",
                List.of(new SeatSelection(2, 1), new SeatSelection(2, 2))
        );

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create(changed)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("student@example.com",
                service.getById(id).customerEmail());

        verify(clients, times(1))
                .createHold(any(ReservationResponse.class));
    }

    @Test
    void shouldStoreRejectedReservationWhenSeatsAreUnavailable() {
        when(clients.createHold(any(ReservationResponse.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT, "Sedišta su zauzeta."
                ));

        var request = request(UUID.randomUUID());
        var result = service.create(request);

        assertEquals("REJECTED", result.status());
        assertEquals("REJECTED",
                service.getById(request.reservationId()).status());
        assertNull(result.expiresAt());
    }

    @Test
    void shouldRecoverAfterTemporaryInventoryFailure() {
        var request = request(UUID.randomUUID());

        when(clients.createHold(any(ReservationResponse.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Privremeni prekid veze."
                ));

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create(request)
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                exception.getStatusCode());
        assertEquals("CREATING",
                service.getById(request.reservationId()).status());

        var hold = new HoldResponse(
                request.reservationId(), 1L, "HELD", expiresAt
        );

        // doReturn koristimo jer prethodno podešen mock baca izuzetak.
        doReturn(hold).when(clients)
                .createHold(any(ReservationResponse.class));

        var recovered = service.create(request);

        assertEquals("AWAITING_PAYMENT", recovered.status());
        assertEquals(request.reservationId(), recovered.id());
        assertEquals(expiresAt, recovered.expiresAt());
    }

    @Test
    void shouldRejectDuplicateSeatsBeforeSaving() {
        UUID id = UUID.randomUUID();

        var request = new CreateReservationRequest(
                id,
                1L,
                "student@example.com",
                List.of(new SeatSelection(2, 1), new SeatSelection(2, 1))
        );

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservations WHERE id = ?
                """, Long.class, id);

        assertEquals(0L, count.longValue());
        verifyNoInteractions(clients);
    }

    private CreateReservationRequest request(UUID id) {
        return new CreateReservationRequest(
                id,
                1L,
                "student@example.com",
                List.of(new SeatSelection(2, 1), new SeatSelection(2, 2))
        );
    }
    
    @Test
    void shouldCancelAndReleaseHoldOnlyOnce() {
        var request = request(UUID.randomUUID());
        service.create(request);

        var releasedHold = new HoldResponse(
                request.reservationId(), 1L, "RELEASED", expiresAt
        );

        when(clients.releaseHold(request.reservationId()))
                .thenReturn(releasedHold);

        var cancelled = service.cancel(request.reservationId());
        var repeated = service.cancel(request.reservationId());

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(cancelled, repeated);

        verify(clients, times(1)).releaseHold(request.reservationId());
    }

    @Test
    void shouldRefreshExpiredReservation() {
        var request = request(UUID.randomUUID());
        service.create(request);

        var expiredHold = new HoldResponse(
                request.reservationId(), 1L, "EXPIRED", expiresAt
        );

        when(clients.getHold(request.reservationId()))
                .thenReturn(expiredHold);

        var result = service.refresh(request.reservationId());

        assertEquals("EXPIRED", result.status());
        assertEquals("EXPIRED",
                service.getById(request.reservationId()).status());
    }
}