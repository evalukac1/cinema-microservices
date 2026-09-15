package com.cinema.movie;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MovieServiceTest {

    private final MovieService movieService = new MovieService();

    @Test
    void shouldReturnAvailableMovies() {
        var movies = movieService.getMovies();

        assertFalse(movies.isEmpty());
        assertTrue(movies.stream()
                .anyMatch(movie -> movie.title().equals("Inception")));
    }

    @Test
    void shouldReturnMovieById() {
        var result = movieService.getMovieById(1L);

        assertTrue(result.isPresent());

        var movie = result.orElseThrow();
        assertEquals(1L, movie.id().longValue());
        assertEquals("Inception", movie.title());
        assertEquals(148, movie.durationMinutes());
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        var result = movieService.getMovieById(999L);

        assertTrue(result.isEmpty());
    }
}