package com.cinema.inventory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.inventory.HoldModels.HoldRequest;
import com.cinema.inventory.HoldModels.HoldResponse;
import com.cinema.inventory.HoldModels.SeatSelection;

@Service
public class HoldService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Duration holdDuration;

    public HoldService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${inventory.hold-duration:PT10M}") Duration holdDuration) {

        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.holdDuration = holdDuration;

        if (holdDuration.isZero() || holdDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "Trajanje zauzimanja mora biti pozitivno."
            );
        }
    }

    public HoldResponse create(HoldRequest request) {
        var seats = request.seats();

        if (seats.stream().distinct().count() != seats.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Isto sedište je navedeno više puta."
            );
        }

        String selection = seats.stream()
                .sorted(Comparator.comparingInt(SeatSelection::rowNumber)
                        .thenComparingInt(SeatSelection::seatNumber))
                .map(seat -> seat.rowNumber() + ":" + seat.seatNumber())
                .collect(Collectors.joining(","));

        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                Instant screeningStart = lockScreening(request.screeningId());
                Instant now = Instant.now();

                expireLocked(request.screeningId(), now);

                var existing = jdbc.query("""
                        SELECT reservation_id, screening_id, status,
                               expires_at, seat_selection
                        FROM seat_holds
                        WHERE reservation_id = ?
                        """,
                        (result, row) -> new ExistingHold(
                                new HoldResponse(
                                        result.getObject("reservation_id", UUID.class),
                                        result.getLong("screening_id"),
                                        result.getString("status"),
                                        result.getTimestamp("expires_at").toInstant()
                                ),
                                result.getString("seat_selection")
                        ),
                        request.reservationId()
                );

                if (!existing.isEmpty()) {
                    var previous = existing.getFirst();

                    if (!previous.response().screeningId()
                            .equals(request.screeningId())
                            || !previous.selection().equals(selection)) {
                        throw conflict(
                                "ID rezervacije je već korišćen za drugi izbor."
                        );
                    }

                    String state = previous.response().status();

                    if (!state.equals("HELD") && !state.equals("CONFIRMED")) {
                        throw conflict("Ovo zauzimanje više nije aktivno.");
                    }

                    return previous.response();
                }

                if (!screeningStart.isAfter(now)) {
                    throw conflict("Projekcija je već počela.");
                }

                for (var seat : seats) {
                    var states = jdbc.query("""
                            SELECT status
                            FROM inventory_seats
                            WHERE screening_id = ?
                              AND row_number = ?
                              AND seat_number = ?
                            """,
                            (result, row) -> result.getString("status"),
                            request.screeningId(),
                            seat.rowNumber(),
                            seat.seatNumber()
                    );

                    if (states.isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Sedište ne postoji."
                        );
                    }

                    if (!states.getFirst().equals("AVAILABLE")) {
                        throw conflict("Jedno ili više sedišta je zauzeto.");
                    }
                }

                Instant expiresAt = now.plus(holdDuration);
                if (expiresAt.isAfter(screeningStart)) {
                    expiresAt = screeningStart;
                }

                jdbc.update("""
                        INSERT INTO seat_holds
                            (reservation_id, screening_id, status,
                             created_at, expires_at, seat_selection)
                        VALUES (?, ?, 'HELD', ?, ?, ?)
                        """,
                        request.reservationId(),
                        request.screeningId(),
                        Timestamp.from(now),
                        Timestamp.from(expiresAt),
                        selection
                );

                for (var seat : seats) {
                    int changed = jdbc.update("""
                            UPDATE inventory_seats
                            SET status = 'HELD', reservation_id = ?
                            WHERE screening_id = ?
                              AND row_number = ?
                              AND seat_number = ?
                              AND status = 'AVAILABLE'
                            """,
                            request.reservationId(),
                            request.screeningId(),
                            seat.rowNumber(),
                            seat.seatNumber()
                    );

                    if (changed != 1) {
                        throw conflict("Sedište više nije dostupno.");
                    }
                }

                return readHold(request.reservationId());
            }));
        } catch (DuplicateKeyException exception) {
            throw conflict("ID rezervacije je već iskorišćen.");
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void expireHolds() {
        var screeningIds = jdbc.query("""
                SELECT DISTINCT screening_id
                FROM seat_holds
                WHERE status = 'HELD' AND expires_at <= CURRENT_TIMESTAMP
                """,
                (result, row) -> result.getLong("screening_id")
        );

        for (Long screeningId : screeningIds) {
            transaction.executeWithoutResult(status -> {
                lockScreening(screeningId);
                expireLocked(screeningId, Instant.now());
            });
        }
    }

    private Instant lockScreening(Long screeningId) {
        var starts = jdbc.query("""
                SELECT starts_at
                FROM inventory_screenings
                WHERE screening_id = ?
                FOR UPDATE
                """,
                (result, row) -> result.getTimestamp("starts_at").toInstant(),
                screeningId
        );

        if (starts.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Prvo pripremi sedišta za projekciju."
            );
        }

        return starts.getFirst();
    }

    private void expireLocked(Long screeningId, Instant now) {
        jdbc.update("""
                UPDATE inventory_seats s
                SET status = 'AVAILABLE', reservation_id = NULL
                FROM seat_holds h
                WHERE s.reservation_id = h.reservation_id
                  AND s.screening_id = ?
                  AND s.status = 'HELD'
                  AND h.status = 'HELD'
                  AND h.expires_at <= ?
                """, screeningId, Timestamp.from(now));

        jdbc.update("""
                UPDATE seat_holds
                SET status = 'EXPIRED'
                WHERE screening_id = ?
                  AND status = 'HELD'
                  AND expires_at <= ?
                """, screeningId, Timestamp.from(now));
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ExistingHold(
            HoldResponse response,
            String selection
    ) {
    }
    
    public HoldResponse confirm(UUID reservationId) {
        HoldResponse response = changeState(reservationId, true);

        if (!response.status().equals("CONFIRMED")) {
            throw conflict(
                    "Potvrda nije moguća. Status: " + response.status()
            );
        }

        return response;
    }

    public HoldResponse release(UUID reservationId) {
        HoldResponse response = changeState(reservationId, false);

        if (response.status().equals("CONFIRMED")) {
            throw conflict("Potvrđena rezervacija ne može ovako da se otkaže.");
        }

        return response;
    }

    public HoldResponse getHold(UUID reservationId) {
        Long screeningId = findScreeningId(reservationId);

        return Objects.requireNonNull(transaction.execute(status -> {
            lockScreening(screeningId);
            expireLocked(screeningId, Instant.now());
            return readHold(reservationId);
        }));
    }

    private HoldResponse changeState(UUID reservationId, boolean confirm) {
        Long screeningId = findScreeningId(reservationId);

        return Objects.requireNonNull(transaction.execute(status -> {
            lockScreening(screeningId);
            expireLocked(screeningId, Instant.now());

            HoldResponse current = readHold(reservationId);

            if (!current.status().equals("HELD")) {
                return current;
            }

            if (confirm) {
                jdbc.update("""
                        UPDATE inventory_seats
                        SET status = 'SOLD'
                        WHERE screening_id = ?
                          AND reservation_id = ?
                          AND status = 'HELD'
                        """, screeningId, reservationId);

                jdbc.update("""
                        UPDATE seat_holds
                        SET status = 'CONFIRMED'
                        WHERE reservation_id = ?
                        """, reservationId);
            } else {
                jdbc.update("""
                        UPDATE inventory_seats
                        SET status = 'AVAILABLE', reservation_id = NULL
                        WHERE screening_id = ?
                          AND reservation_id = ?
                          AND status = 'HELD'
                        """, screeningId, reservationId);

                jdbc.update("""
                        UPDATE seat_holds
                        SET status = 'RELEASED'
                        WHERE reservation_id = ?
                        """, reservationId);
            }

            return readHold(reservationId);
        }));
    }

    private Long findScreeningId(UUID reservationId) {
        var ids = jdbc.query("""
                SELECT screening_id
                FROM seat_holds
                WHERE reservation_id = ?
                """,
                (result, row) -> result.getLong("screening_id"),
                reservationId
        );

        if (ids.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Zauzimanje ne postoji."
            );
        }

        return ids.getFirst();
    }

    private HoldResponse readHold(UUID reservationId) {
        var holds = jdbc.query("""
                SELECT reservation_id, screening_id, status, expires_at
                FROM seat_holds
                WHERE reservation_id = ?
                """,
                (result, row) -> new HoldResponse(
                        result.getObject("reservation_id", UUID.class),
                        result.getLong("screening_id"),
                        result.getString("status"),
                        result.getTimestamp("expires_at").toInstant()
                ),
                reservationId
        );

        if (holds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Zauzimanje ne postoji."
            );
        }

        return holds.getFirst();
    }
}