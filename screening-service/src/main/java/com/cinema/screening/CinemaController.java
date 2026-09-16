package com.cinema.screening;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cinema.screening.ApiModels.*;

@RestController
@RequestMapping("/api")
public class CinemaController {

    private final HallService halls;
    private final ScreeningService screenings;

    public CinemaController(HallService halls, ScreeningService screenings) {
        this.halls = halls;
        this.screenings = screenings;
    }

    @GetMapping("/halls")
    public List<HallResponse> getHalls() {
        return halls.getAll();
    }

    @GetMapping("/halls/{id}")
    public HallResponse getHall(@PathVariable("id") Long id) {
        return halls.getById(id);
    }

    @PostMapping("/halls")
    public ResponseEntity<HallResponse> createHall(
            @Valid @RequestBody CreateHallRequest request) {
        var hall = halls.create(request);
        return ResponseEntity.created(
                URI.create("/api/halls/" + hall.id())
        ).body(hall);
    }

    @GetMapping("/screenings")
    public List<ScreeningResponse> getScreenings() {
        return screenings.getAll();
    }

    @GetMapping("/screenings/{id}")
    public ScreeningResponse getScreening(@PathVariable("id") Long id) {
        return screenings.getById(id);
    }

    @PostMapping("/screenings")
    public ResponseEntity<ScreeningResponse> createScreening(
            @Valid @RequestBody CreateScreeningRequest request) {
        var screening = screenings.create(request);
        return ResponseEntity.created(
                URI.create("/api/screenings/" + screening.id())
        ).body(screening);
    }
}