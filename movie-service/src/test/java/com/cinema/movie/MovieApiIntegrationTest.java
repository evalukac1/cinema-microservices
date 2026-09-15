package com.cinema.movie;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MovieApiIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
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
        assertTrue(movies.length > 0);
    }

    @Test
    void shouldReturnExistingMovie() {
        var response = client.get()
                .uri("/api/movies/1")
                .retrieve()
                .toEntity(MovieResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var movie = response.getBody();
        assertNotNull(movie);
        assertEquals(1L, movie.id().longValue());
        assertEquals("Inception", movie.title());
        assertEquals(148, movie.durationMinutes());
    }

    @Test
    void shouldReturn404ForUnknownMovie() {
        var exception = assertThrows(
                HttpClientErrorException.NotFound.class,
                () -> client.get()
                        .uri("/api/movies/999")
                        .retrieve()
                        .toBodilessEntity()
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }
}