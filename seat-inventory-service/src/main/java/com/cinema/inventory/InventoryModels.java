package com.cinema.inventory;

import java.time.Instant;
import java.util.List;

public final class InventoryModels {

    private InventoryModels() {
    }

    public record ScreeningInfo(
            Long id,
            Long movieId,
            Long hallId,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    public record HallInfo(
            Long id,
            String name,
            int rowCount,
            int seatsPerRow
    ) {
    }

    public record SeatResponse(
            int rowNumber,
            int seatNumber,
            String status
    ) {
    }

    public record InventoryResponse(
            Long screeningId,
            Instant startsAt,
            List<SeatResponse> seats
    ) {
    }
}