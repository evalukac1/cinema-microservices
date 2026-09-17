package com.cinema.payment;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

import com.cinema.payment.PaymentMessages.PaymentResponse;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping("/reservation/{reservationId}")
    public PaymentResponse getByReservation(
            @PathVariable("reservationId") UUID reservationId) {
        return service.getByReservation(reservationId);
    }
}