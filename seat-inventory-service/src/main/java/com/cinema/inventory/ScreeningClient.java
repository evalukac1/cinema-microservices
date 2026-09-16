package com.cinema.inventory;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.inventory.InventoryModels.HallInfo;
import com.cinema.inventory.InventoryModels.ScreeningInfo;

@Component
public class ScreeningClient {

    private final RestClient client;

    public ScreeningClient(
            @Value("${clients.screening-service.url}") String baseUrl) {

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public ScreeningInfo getScreening(Long id) {
        var result = get("/api/screenings/{id}", id, ScreeningInfo.class);

        if (!id.equals(result.id()) || result.hallId() == null
                || result.startsAt() == null) {
            throw invalidResponse();
        }

        return result;
    }

    public HallInfo getHall(Long id) {
        var result = get("/api/halls/{id}", id, HallInfo.class);

        if (!id.equals(result.id())
                || result.rowCount() < 1 || result.rowCount() > 26
                || result.seatsPerRow() < 1 || result.seatsPerRow() > 50) {
            throw invalidResponse();
        }

        return result;
    }

    private <T> T get(String path, Long id, Class<T> type) {
        try {
            T result = client.get()
                    .uri(path, id)
                    .retrieve()
                    .body(type);

            if (result == null) {
                throw invalidResponse();
            }

            return result;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Projekcija ili sala ne postoji."
            );
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Servis za projekcije trenutno nije dostupan."
            );
        }
    }

    private ResponseStatusException invalidResponse() {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Servis za projekcije je vratio neispravne podatke."
        );
    }
}