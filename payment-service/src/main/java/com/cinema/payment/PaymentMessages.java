package com.cinema.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.*;

public final class PaymentMessages {

    private PaymentMessages() {
    }

    public record PaymentRequested(
            @NotNull UUID eventId,
            @NotNull UUID reservationId,
            @NotNull @DecimalMin("0.01")
            @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull Instant expiresAt,
            @NotNull Boolean simulateFailure
    ) {
    }

    public record PaymentResult(
            UUID eventId,
            UUID paymentId,
            UUID reservationId,
            BigDecimal amount,
            String currency,
            String status,
            String failureReason
    ) {
    }

    public record PaymentResponse(
            UUID id,
            UUID reservationId,
            BigDecimal amount,
            String currency,
            String status,
            String failureReason
    ) {
    }
}