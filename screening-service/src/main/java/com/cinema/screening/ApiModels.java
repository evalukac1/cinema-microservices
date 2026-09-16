package com.cinema.screening;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.*;

public final class ApiModels {

    private ApiModels() {
    }

    public record CreateHallRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(26) int rowCount,
            @Min(1) @Max(50) int seatsPerRow
    ) {
    }

    public record HallResponse(
            Long id,
            String name,
            int rowCount,
            int seatsPerRow
    ) {
    }

    public record CreateScreeningRequest(
            @NotNull @Positive Long movieId,
            @NotNull @Positive Long hallId,
            @NotNull @Future OffsetDateTime startsAt,
            @NotNull
            @DecimalMin("0.01")
            @Digits(integer = 8, fraction = 2)
            BigDecimal ticketPrice,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency
    ) {
    }

    public record ScreeningResponse(
            Long id,
            Long movieId,
            Long hallId,
            Instant startsAt,
            Instant endsAt,
            BigDecimal ticketPrice,
            String currency
    ) {
    }

    public record MovieResponse(
            Long id,
            String title,
            String genre,
            int durationMinutes
    ) {
    }
}