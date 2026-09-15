package com.cinema.movie;

public record MovieResponse(
        Long id,
        String title,
        String genre,
        int durationMinutes
) {
}