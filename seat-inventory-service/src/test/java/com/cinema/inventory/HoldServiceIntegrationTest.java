package com.cinema.inventory;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.inventory.HoldModels.HoldRequest;
import com.cinema.inventory.HoldModels.SeatSelection;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldServiceIntegrationTest {

    @Autowired
    HoldService service;

    @Autowired
    JdbcTemplate jdbc;

    Long screeningId;

    @BeforeEach
    void setUp() {
        // Svaki test dobija drugu projekciju.
        screeningId = jdbc.queryForObject("""
                INSERT INTO inventory_screenings
                    (screening_id, starts_at, row_count, seats_per_row)
                SELECT COALESCE(MAX(screening_id), 0) + 1, ?, 1, 3
                FROM inventory_screenings
                RETURNING screening_id
                """,
                Long.class,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS))
        );

        jdbc.update("""
                INSERT INTO inventory_seats
                    (screening_id, row_number, seat_number, status)
                SELECT ?, 1, number, 'AVAILABLE'
                FROM generate_series(1, 3) AS seats(number)
                """, screeningId);
    }

    @Test
    void shouldReturnSameHoldForRepeatedRequest() {
        var request = request(UUID.randomUUID(), 1, 2);

        var first = service.create(request);
        var repeated = service.create(request);

        assertEquals(first, repeated);
        assertEquals("HELD", seatStatus(1));
        assertEquals("HELD", seatStatus(2));

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM seat_holds
                WHERE reservation_id = ?
                """, Long.class, request.reservationId());

        assertEquals(1L, count.longValue());
    }

    @Test
    void shouldRejectReusingReservationIdForDifferentSeats() {
        UUID id = UUID.randomUUID();
        service.create(request(id, 1));

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create(request(id, 2))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("AVAILABLE", seatStatus(2));
    }

    @Test
    void shouldNotPartiallyHoldSeats() {
        service.create(request(UUID.randomUUID(), 1));

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create(request(UUID.randomUUID(), 2, 1))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("AVAILABLE", seatStatus(2));
    }

    @Test
    void onlyOneConcurrentRequestShouldSucceed() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(
                    () -> attemptHold(ready, start)
            );
            Future<Integer> second = executor.submit(
                    () -> attemptHold(ready, start)
            );

            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(bothReady);

            var results = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );

            assertEquals(1L,
                    results.stream().filter(code -> code == 200).count());
            assertEquals(1L,
                    results.stream().filter(code -> code == 409).count());

            assertEquals("HELD", seatStatus(1));

            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM seat_holds
                    WHERE screening_id = ?
                    """, Long.class, screeningId);

            assertEquals(1L, count.longValue());
        }
    }

    @Test
    void shouldConfirmOnlyOnceAndPreventRelease() {
        UUID id = UUID.randomUUID();
        service.create(request(id, 1));

        var confirmed = service.confirm(id);

        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(confirmed, service.confirm(id));
        assertEquals("SOLD", seatStatus(1));

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.release(id)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("SOLD", seatStatus(1));
    }

    @Test
    void shouldReleaseOnlyOnce() {
        UUID id = UUID.randomUUID();
        service.create(request(id, 1, 2));

        var released = service.release(id);

        assertEquals("RELEASED", released.status());
        assertEquals(released, service.release(id));
        assertEquals("AVAILABLE", seatStatus(1));
        assertEquals("AVAILABLE", seatStatus(2));
    }

    @Test
    void shouldRejectConfirmationAfterExpiration() {
        UUID id = UUID.randomUUID();
        service.create(request(id, 1));

        makeExpired(id);

        var exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirm(id)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("EXPIRED", service.getHold(id).status());
        assertEquals("AVAILABLE", seatStatus(1));
    }

    @Test
    void shouldReleaseExpiredHoldsDuringCleanup() {
        UUID id = UUID.randomUUID();
        service.create(request(id, 1));

        makeExpired(id);
        service.expireHolds();

        assertEquals("AVAILABLE", seatStatus(1));

        String status = jdbc.queryForObject("""
                SELECT status FROM seat_holds
                WHERE reservation_id = ?
                """, String.class, id);

        assertEquals("EXPIRED", status);
    }

    private int attemptHold(
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Test nije dobio signal za start.");
        }

        try {
            service.create(request(UUID.randomUUID(), 1));
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private HoldRequest request(UUID id, int... seatNumbers) {
        var seats = java.util.Arrays.stream(seatNumbers)
                .mapToObj(number -> new SeatSelection(1, number))
                .toList();

        return new HoldRequest(id, screeningId, seats);
    }

    private String seatStatus(int seatNumber) {
        return jdbc.queryForObject("""
                SELECT status FROM inventory_seats
                WHERE screening_id = ?
                  AND row_number = 1
                  AND seat_number = ?
                """, String.class, screeningId, seatNumber);
    }

    private void makeExpired(UUID id) {
        // Menjamo vreme samo u test bazi, bez čekanja deset minuta.
        jdbc.update("""
                UPDATE seat_holds
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE reservation_id = ?
                """, id);
    }
}