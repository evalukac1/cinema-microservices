package com.cinema.reservation;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cinema.reservation.ReservationModels.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService service;
    private final ReservationPaymentService payments;

    public ReservationController(
            ReservationService service,
            ReservationPaymentService payments) {
        this.service = service;
        this.payments = payments;
    }
    
    @PostMapping
    public ResponseEntity<ReservationResponse> create(
            @Valid @RequestBody CreateReservationRequest request) {

        var reservation = service.create(request);

        if (reservation.status().equals("REJECTED")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(reservation);
        }

        return ResponseEntity.created(
                URI.create("/api/reservations/" + reservation.id())
        ).body(reservation);
    }

    @GetMapping("/{id}")
    public ReservationResponse getById(@PathVariable("id") UUID id) {
        return service.refresh(id);
    }
    
    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") UUID id) {
        return service.cancel(id);
    }
    
    @PostMapping("/{id}/pay")
    public ResponseEntity<ReservationResponse> pay(
            @PathVariable("id") UUID id,
            @Valid @RequestBody PaymentRequest request) {

        return ResponseEntity.accepted()
                .body(payments.requestPayment(id, request));
    }
}