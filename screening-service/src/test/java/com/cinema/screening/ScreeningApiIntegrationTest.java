package com.cinema.screening;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.screening.ApiModels.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ScreeningApiIntegrationTest {

    @Autowired
    HallRepository halls;

    @Autowired
    ScreeningRepository screenings;

    @MockitoBean
    MovieClient movieClient;

    @Value("${local.server.port}")
    int port;

    RestClient client;
    Long hallId;
    OffsetDateTime start;

    @BeforeEach
    void setUp() {
        screenings.deleteAll();
        halls.deleteAll();

        var hall = halls.saveAndFlush(new Hall("Sala 1", 5, 8));
        hallId = hall.id;

        start = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(2)
                .withNano(0);

        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        when(movieClient.getMovie(1L))
                .thenReturn(new MovieResponse(1L, "Inception", "SCI_FI", 148));
    }

    @Test
    void shouldCreateHall() {
        var response = client.post()
                .uri("/api/halls")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateHallRequest("Sala 2", 6, 10))
                .retrieve()
                .toEntity(HallResponse.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(6, response.getBody().rowCount());
        assertEquals(2, halls.count());
    }

    @Test
    void shouldRejectInvalidHall() {
        assertThrows(HttpClientErrorException.BadRequest.class,
                () -> client.post()
                        .uri("/api/halls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateHallRequest("Sala 2", 0, 10))
                        .retrieve()
                        .toBodilessEntity());

        assertEquals(1, halls.count());
    }

    @Test
    void shouldCreateScreeningWithCalculatedEnd() {
        var created = createScreening(start);

        assertNotNull(created);
        assertEquals(start.toInstant(), created.startsAt());
        assertEquals(
                start.plusMinutes(148).toInstant(),
                created.endsAt()
        );
        assertEquals(1, screenings.count());

        var fetched = client.get()
                .uri("/api/screenings/{id}", created.id())
                .retrieve()
                .body(ScreeningResponse.class);

        assertEquals(created, fetched);
    }

    @Test
    void shouldRejectOverlappingScreening() {
        createScreening(start);

        assertThrows(HttpClientErrorException.Conflict.class,
                () -> createScreening(start.plusMinutes(30)));

        assertEquals(1, screenings.count());
    }

    @Test
    void shouldAllowScreeningStartingAtPreviousEnd() {
        createScreening(start);
        createScreening(start.plusMinutes(148));

        assertEquals(2, screenings.count());
    }

    @Test
    void shouldRejectUnknownMovie() {
        when(movieClient.getMovie(1L))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Film ne postoji."
                ));

        assertThrows(HttpClientErrorException.NotFound.class,
                () -> createScreening(start));

        assertEquals(0, screenings.count());
    }

    private ScreeningResponse createScreening(OffsetDateTime startsAt) {
        var request = new CreateScreeningRequest(
                1L,
                hallId,
                startsAt,
                new BigDecimal("8.50"),
                "EUR"
        );

        var response = client.post()
                .uri("/api/screenings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(ScreeningResponse.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody();
    }
}