package com.cinema.movie;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class MovieServiceTest {

    private MovieRepository repository;
    private MovieService service;

    @BeforeEach
    void setUp() {
        repository = mock(MovieRepository.class);
        service = new MovieService(repository);
    }

    @Test
    void shouldMapMoviesFromRepository() {
        var movie = sampleMovie();

        when(repository.findAll(Sort.by("id")))
                .thenReturn(List.of(movie));

        var result = service.getMovies();

        assertEquals(
                List.of(new MovieResponse(7L, "Inception", "SCI_FI", 148)),
                result
        );
    }

    @Test
    void shouldReturnMovieById() {
        var movie = sampleMovie();

        when(repository.findById(7L))
                .thenReturn(Optional.of(movie));

        var result = service.getMovieById(7L);

        assertEquals(
                Optional.of(
                        new MovieResponse(7L, "Inception", "SCI_FI", 148)
                ),
                result
        );
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        when(repository.findById(999L))
                .thenReturn(Optional.empty());

        assertTrue(service.getMovieById(999L).isEmpty());
    }

    private Movie sampleMovie() {
        var movie = mock(Movie.class);

        when(movie.getId()).thenReturn(7L);
        when(movie.getTitle()).thenReturn("Inception");
        when(movie.getGenre()).thenReturn("SCI_FI");
        when(movie.getDurationMinutes()).thenReturn(148);

        return movie;
    }
}