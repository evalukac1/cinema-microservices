package com.cinema.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ReservationModels {

    private ReservationModels() {
    }

    public record SeatSelection(
            @Min(1) @Max(26) int rowNumber,
            @Min(1) @Max(50) int seatNumber
    ) {
    }

    public record CreateReservationRequest(
            @NotNull UUID reservationId,
            @NotNull @Positive Long screeningId,
            @NotBlank @Email @Size(max = 254) String customerEmail,
            @NotEmpty @Size(max = 10)
            List<@NotNull @Valid SeatSelection> seats
    ) {
    }

    public record ReservationResponse(
            UUID id,
            Long screeningId,
            String customerEmail,
            String status,
            BigDecimal unitPrice,
            BigDecimal totalPrice,
            String currency,
            Instant expiresAt,
            List<SeatSelection> seats
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScreeningInfo(
            Long id,
            Instant startsAt,
            BigDecimal ticketPrice,
            String currency
    ) {
    }

    public record HoldRequest(
            UUID reservationId,
            Long screeningId,
            List<SeatSelection> seats
    ) {
    }

    public record HoldResponse(
            UUID reservationId,
            Long screeningId,
            String status,
            Instant expiresAt
    ) {
    }
}