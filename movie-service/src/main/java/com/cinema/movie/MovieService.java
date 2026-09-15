package com.cinema.movie;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MovieService {

    private final MovieRepository movieRepository;

    public MovieService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    public List<MovieResponse> getMovies() {
        return movieRepository.findAll(Sort.by("id")).stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<MovieResponse> getMovieById(Long id) {
        return movieRepository.findById(id)
                .map(this::toResponse);
    }

    private MovieResponse toResponse(Movie movie) {
        return new MovieResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getGenre(),
                movie.getDurationMinutes()
        );
    }
}