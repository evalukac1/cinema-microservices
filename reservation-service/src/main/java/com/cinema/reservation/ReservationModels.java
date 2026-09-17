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
    
    public record PaymentRequest(
            @NotNull Boolean simulateFailure
    ) {
    }

    public record PaymentRequested(
            UUID eventId,
            UUID reservationId,
            BigDecimal amount,
            String currency,
            Instant expiresAt,
            Boolean simulateFailure
    ) {
    }
    
    public record PaymentResult(
            @NotNull UUID eventId,
            @NotNull UUID paymentId,
            @NotNull UUID reservationId,
            @NotNull @DecimalMin("0.01")
            @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
            @Size(max = 250) String failureReason
    ) {
    }
}