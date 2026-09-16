package com.cinema.reservation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.reservation.ReservationModels.*;

@Service
public class ReservationService {

    private final JdbcTemplate jdbc;
    private final ReservationClients clients;
    private final TransactionTemplate transaction;

    public ReservationService(
            JdbcTemplate jdbc,
            ReservationClients clients,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ReservationResponse create(CreateReservationRequest request) {
        var seats = sortedSeats(request.seats());
        String email = request.customerEmail().strip();

        if (seats.stream().distinct().count() != seats.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Isto sedište je navedeno više puta."
            );
        }

        if (!exists(request.reservationId())) {
            var screening = clients.getScreening(request.screeningId());

            if (!screening.startsAt().isAfter(Instant.now())) {
                throw conflict("Projekcija je već počela.");
            }

            BigDecimal total = screening.ticketPrice()
                    .multiply(BigDecimal.valueOf(seats.size()));

            transaction.executeWithoutResult(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO reservations
                            (id, screening_id, customer_email, status,
                             unit_price, total_price, currency, seat_count,
                             created_at, updated_at)
                        VALUES (?, ?, ?, 'CREATING', ?, ?, ?, ?,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (id) DO NOTHING
                        """,
                        request.reservationId(),
                        request.screeningId(),
                        email,
                        screening.ticketPrice(),
                        total,
                        screening.currency(),
                        seats.size()
                );

                if (inserted == 1) {
                    for (var seat : seats) {
                        jdbc.update("""
                                INSERT INTO reservation_seats
                                    (reservation_id, row_number, seat_number)
                                VALUES (?, ?, ?)
                                """,
                                request.reservationId(),
                                seat.rowNumber(),
                                seat.seatNumber()
                        );
                    }
                }
            });
        }

        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.queryForObject("""
                    SELECT id FROM reservations
                    WHERE id = ?
                    FOR UPDATE
                    """, UUID.class, request.reservationId());

            var current = getById(request.reservationId());

            if (!current.screeningId().equals(request.screeningId())
                    || !current.customerEmail().equals(email)
                    || !current.seats().equals(seats)) {
                throw conflict(
                        "ID rezervacije je već korišćen za drugačiji zahtev."
                );
            }

            if (!current.status().equals("CREATING")) {
                return current;
            }

            HoldResponse hold;

            try {
                hold = clients.createHold(current);
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 409) {
                    throw exception;
                }

                jdbc.update("""
                        UPDATE reservations
                        SET status = 'REJECTED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, current.id());

                return getById(current.id());
            }

            jdbc.update("""
                    UPDATE reservations
                    SET status = 'AWAITING_PAYMENT',
                        expires_at = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    Timestamp.from(hold.expiresAt()),
                    current.id()
            );

            return getById(current.id());
        }));
    }

    public ReservationResponse getById(UUID id) {
        var results = jdbc.query("""
                SELECT id, screening_id, customer_email, status,
                       unit_price, total_price, currency, expires_at
                FROM reservations
                WHERE id = ?
                """,
                (result, row) -> {
                    Timestamp expiration = result.getTimestamp("expires_at");

                    return new ReservationResponse(
                            result.getObject("id", UUID.class),
                            result.getLong("screening_id"),
                            result.getString("customer_email"),
                            result.getString("status"),
                            result.getBigDecimal("unit_price"),
                            result.getBigDecimal("total_price"),
                            result.getString("currency"),
                            expiration == null ? null : expiration.toInstant(),
                            List.of()
                    );
                },
                id
        );

        if (results.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Rezervacija ne postoji."
            );
        }

        var seats = jdbc.query("""
                SELECT row_number, seat_number
                FROM reservation_seats
                WHERE reservation_id = ?
                ORDER BY row_number, seat_number
                """,
                (result, row) -> new SeatSelection(
                        result.getInt("row_number"),
                        result.getInt("seat_number")
                ),
                id
        );

        var reservation = results.getFirst();

        return new ReservationResponse(
                reservation.id(),
                reservation.screeningId(),
                reservation.customerEmail(),
                reservation.status(),
                reservation.unitPrice(),
                reservation.totalPrice(),
                reservation.currency(),
                reservation.expiresAt(),
                seats
        );
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM reservations WHERE id = ?
                )
                """, Boolean.class, id));
    }

    private List<SeatSelection> sortedSeats(List<SeatSelection> seats) {
        return seats.stream()
                .sorted(Comparator.comparingInt(SeatSelection::rowNumber)
                        .thenComparingInt(SeatSelection::seatNumber))
                .toList();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    
    public ReservationResponse refresh(UUID id) {
        // Ako je prethodni pokušaj otkazivanja prekinut, nastavljamo ga.
        if (getById(id).status().equals("CANCEL_PENDING")) {
            return cancel(id);
        }

        return Objects.requireNonNull(transaction.execute(status -> {
            lockReservation(id);

            var current = getById(id);

            if (!current.status().equals("AWAITING_PAYMENT")) {
                return current;
            }

            var hold = clients.getHold(id);
            validateScreening(current, hold);

            switch (hold.status()) {
                case "EXPIRED" -> updateStatus(id, "EXPIRED");
                case "RELEASED" -> updateStatus(id, "CANCELLED");
                case "HELD" -> {
                    // Zauzimanje je i dalje aktivno.
                }
                default -> throw conflict(
                        "Status sedišta nije usklađen sa rezervacijom."
                );
            }

            return getById(id);
        }));
    }

    public ReservationResponse cancel(UUID id) {
        var prepared = Objects.requireNonNull(transaction.execute(status -> {
            lockReservation(id);

            var current = getById(id);

            switch (current.status()) {
                case "CANCELLED", "EXPIRED", "REJECTED", "CANCEL_PENDING" -> {
                    return current;
                }
                case "AWAITING_PAYMENT" -> {
                    updateStatus(id, "CANCEL_PENDING");
                    return getById(id);
                }
                default -> throw conflict(
                        "Otkazivanje nije dozvoljeno u statusu "
                                + current.status()
                );
            }
        }));

        if (!prepared.status().equals("CANCEL_PENDING")) {
            return prepared;
        }

        return Objects.requireNonNull(transaction.execute(status -> {
            lockReservation(id);

            var current = getById(id);

            if (!current.status().equals("CANCEL_PENDING")) {
                return current;
            }

            var hold = clients.releaseHold(id);
            validateScreening(current, hold);

            switch (hold.status()) {
                case "RELEASED" -> updateStatus(id, "CANCELLED");
                case "EXPIRED" -> updateStatus(id, "EXPIRED");
                default -> throw conflict(
                        "Servis sedišta nije potvrdio oslobađanje."
                );
            }

            return getById(id);
        }));
    }

    private void lockReservation(UUID id) {
        var ids = jdbc.query("""
                SELECT id FROM reservations
                WHERE id = ?
                FOR UPDATE
                """,
                (result, row) -> result.getObject("id", UUID.class),
                id
        );

        if (ids.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Rezervacija ne postoji."
            );
        }
    }

    private void updateStatus(UUID id, String newStatus) {
        jdbc.update("""
                UPDATE reservations
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, newStatus, id);
    }

    private void validateScreening(
            ReservationResponse reservation,
            HoldResponse hold) {

        if (!reservation.screeningId().equals(hold.screeningId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Zauzimanje pripada drugoj projekciji."
            );
        }
    }
}