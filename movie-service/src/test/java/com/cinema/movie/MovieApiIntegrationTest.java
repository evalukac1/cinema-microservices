package com.cinema.movie;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MovieApiIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MovieRepository repository;

    private RestClient client;
    private Long movieId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();

        var movie = repository.saveAndFlush(
                new Movie("Inception", "SCI_FI", 148)
        );
        movieId = movie.getId();

        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void shouldReturnMoviesAsJson() {
        var response = client.get()
                .uri("/api/movies")
                .retrieve()
                .toEntity(MovieResponse[].class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var movies = response.getBody();
        assertNotNull(movies);
        assertEquals(1, movies.length);
        assertEquals(
                new MovieResponse(movieId, "Inception", "SCI_FI", 148),
                movies[0]
        );
    }

    @Test
    void shouldReturnExistingMovie() {
        var response = client.get()
                .uri("/api/movies/{id}", movieId)
                .retrieve()
                .toEntity(MovieResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                new MovieResponse(movieId, "Inception", "SCI_FI", 148),
                response.getBody()
        );
    }

    @Test
    void shouldReturn404ForUnknownMovie() {
        // Brišemo film da njegov ID sigurno više ne postoji.
        repository.deleteById(movieId);

        var exception = assertThrows(
                HttpClientErrorException.NotFound.class,
                () -> client.get()
                        .uri("/api/movies/{id}", movieId)
                        .retrieve()
                        .toBodilessEntity()
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void shouldReturnEmptyListWhenNoMoviesExist() {
        repository.deleteAll();

        var response = client.get()
                .uri("/api/movies")
                .retrieve()
                .toEntity(MovieResponse[].class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().length);
    }
}