package com.cinema.inventory;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import com.cinema.inventory.HoldModels.HoldRequest;
import com.cinema.inventory.HoldModels.HoldResponse;

@RestController
@RequestMapping("/api/inventory/holds")
public class HoldController {

    private final HoldService service;

    public HoldController(HoldService service) {
        this.service = service;
    }

    @PostMapping
    public HoldResponse create(@Valid @RequestBody HoldRequest request) {
        return service.create(request);
    }

    @GetMapping("/{reservationId}")
    public HoldResponse getHold(
            @PathVariable("reservationId") UUID reservationId) {
        return service.getHold(reservationId);
    }

    @PostMapping("/{reservationId}/confirm")
    public HoldResponse confirm(
            @PathVariable("reservationId") UUID reservationId) {
        return service.confirm(reservationId);
    }

    @PostMapping("/{reservationId}/release")
    public HoldResponse release(
            @PathVariable("reservationId") UUID reservationId) {
        return service.release(reservationId);
    }
}