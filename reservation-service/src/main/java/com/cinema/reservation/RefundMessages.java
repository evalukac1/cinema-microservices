package com.cinema.reservation;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class RefundMessages {

    private RefundMessages() {
    }

    public record RefundRequested(
            UUID eventId,
            UUID paymentId,
            UUID reservationId,
            BigDecimal amount,
            String currency
    ) {
    }

    public record RefundResult(
            @NotNull UUID eventId,
            @NotNull UUID paymentId,
            @NotNull UUID reservationId,

            @NotNull
            @DecimalMin("0.01")
            @Digits(integer = 10, fraction = 2)
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "[A-Z]{3}")
            String currency,

            @NotBlank
            @Pattern(regexp = "REFUNDED")
            String status
    ) {
    }
}