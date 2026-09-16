package com.cinema.inventory;

import org.springframework.web.bind.annotation.*;

import com.cinema.inventory.InventoryModels.InventoryResponse;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @PostMapping("/screenings/{screeningId}/initialize")
    public InventoryResponse initialize(
            @PathVariable("screeningId") Long screeningId) {
        return service.initialize(screeningId);
    }

    @GetMapping("/screenings/{screeningId}/seats")
    public InventoryResponse getSeats(
            @PathVariable("screeningId") Long screeningId) {
        return service.getInventory(screeningId);
    }
}