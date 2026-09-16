package com.cinema.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class HoldModels {

    private HoldModels() {
    }

    public record SeatSelection(
            @Min(1) @Max(26) int rowNumber,
            @Min(1) @Max(50) int seatNumber
    ) {
    }

    public record HoldRequest(
            @NotNull UUID reservationId,
            @NotNull @Positive Long screeningId,
            @NotEmpty @Size(max = 10)
            List<@NotNull @Valid SeatSelection> seats
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