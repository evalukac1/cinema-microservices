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
import org.springframework.http.MediaType;

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
    
    @Test
    void shouldCreateMovieAndMakeItAvailable() {
        long countBefore = repository.count();

        var request = new CreateMovieRequest(
                "  Interstellar  ", "SCI_FI", 169
        );

        var response = client.post()
                .uri("/api/movies")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(MovieResponse.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        var created = response.getBody();
        assertNotNull(created);
        assertNotNull(created.id());
        assertEquals("Interstellar", created.title());
        assertEquals("SCI_FI", created.genre());
        assertEquals(169, created.durationMinutes());

        var location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertEquals("/api/movies/" + created.id(), location.toString());

        assertEquals(countBefore + 1, repository.count());

        var fetched = client.get()
                .uri(location.toString())
                .retrieve()
                .body(MovieResponse.class);

        assertEquals(created, fetched);
    }

    @Test
    void shouldRejectBlankTitle() {
        long countBefore = repository.count();

        var request = new CreateMovieRequest("   ", "SCI_FI", 148);

        var exception = assertThrows(
                HttpClientErrorException.BadRequest.class,
                () -> client.post()
                        .uri("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(countBefore, repository.count());
    }

    @Test
    void shouldRejectZeroDuration() {
        long countBefore = repository.count();

        var request = new CreateMovieRequest("Interstellar", "SCI_FI", 0);

        var exception = assertThrows(
                HttpClientErrorException.BadRequest.class,
                () -> client.post()
                        .uri("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(countBefore, repository.count());
    }
}