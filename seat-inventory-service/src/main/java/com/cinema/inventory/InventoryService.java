package com.cinema.inventory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.inventory.InventoryModels.InventoryResponse;
import com.cinema.inventory.InventoryModels.SeatResponse;

@Service
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final ScreeningClient screeningClient;
    private final TransactionTemplate transaction;

    public InventoryService(
            JdbcTemplate jdbc,
            ScreeningClient screeningClient,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.screeningClient = screeningClient;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public InventoryResponse initialize(Long screeningId) {
        if (exists(screeningId)) {
            return getInventory(screeningId);
        }

        var screening = screeningClient.getScreening(screeningId);

        if (!screening.startsAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Nije moguće pripremiti sedišta za započetu projekciju."
            );
        }

        var hall = screeningClient.getHall(screening.hallId());

        return Objects.requireNonNull(transaction.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO inventory_screenings
                        (screening_id, starts_at, row_count, seats_per_row)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (screening_id) DO NOTHING
                    """,
                    screeningId,
                    Timestamp.from(screening.startsAt()),
                    hall.rowCount(),
                    hall.seatsPerRow()
            );

            if (inserted == 1) {
                jdbc.update("""
                        INSERT INTO inventory_seats
                            (screening_id, row_number, seat_number, status)
                        SELECT ?, rows.number, seats.number, 'AVAILABLE'
                        FROM generate_series(1, ?) AS rows(number)
                        CROSS JOIN generate_series(1, ?) AS seats(number)
                        """,
                        screeningId,
                        hall.rowCount(),
                        hall.seatsPerRow()
                );
            }

            return getInventory(screeningId);
        }));
    }

    public InventoryResponse getInventory(Long screeningId) {
        var starts = jdbc.query("""
                SELECT starts_at
                FROM inventory_screenings
                WHERE screening_id = ?
                """,
                (result, row) -> result.getTimestamp("starts_at").toInstant(),
                screeningId
        );

        if (starts.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Sedišta za ovu projekciju još nisu pripremljena."
            );
        }

        var seats = jdbc.query("""
                SELECT row_number, seat_number, status
                FROM inventory_seats
                WHERE screening_id = ?
                ORDER BY row_number, seat_number
                """,
                (result, row) -> new SeatResponse(
                        result.getInt("row_number"),
                        result.getInt("seat_number"),
                        result.getString("status")
                ),
                screeningId
        );

        return new InventoryResponse(screeningId, starts.getFirst(), seats);
    }

    private boolean exists(Long screeningId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM inventory_screenings
                    WHERE screening_id = ?
                )
                """, Boolean.class, screeningId));
    }
}