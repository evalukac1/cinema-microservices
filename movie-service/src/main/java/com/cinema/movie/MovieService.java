package com.cinema.movie;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class MovieService {

    private final List<MovieResponse> movies = List.of(
            new MovieResponse(1L, "Inception", "SCI_FI", 148),
            new MovieResponse(2L, "The Grand Budapest Hotel", "COMEDY", 99)
    );

    public List<MovieResponse> getMovies() {
        return movies;
    }

    public Optional<MovieResponse> getMovieById(Long id) {
        return movies.stream()
                .filter(movie -> movie.id().equals(id))
                .findFirst();
    }
}