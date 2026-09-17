package com.cinema.reservation;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.reservation.ReservationModels.*;
import java.util.UUID;

@Component
public class ReservationClients {

    private final RestClient screenings;
    private final RestClient inventory;

    public ReservationClients(
            @Value("${clients.screening-service.url}") String screeningUrl,
            @Value("${clients.inventory-service.url}") String inventoryUrl) {
        screenings = buildClient(screeningUrl);
        inventory = buildClient(inventoryUrl);
    }

    public ScreeningInfo getScreening(Long id) {
        try {
            var screening = screenings.get()
                    .uri("/api/screenings/{id}", id)
                    .retrieve()
                    .body(ScreeningInfo.class);

            if (screening == null
                    || !id.equals(screening.id())
                    || screening.startsAt() == null
                    || screening.ticketPrice() == null
                    || screening.ticketPrice().signum() <= 0
                    || screening.currency() == null
                    || !screening.currency().matches("[A-Z]{3}")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Projekcija ima neispravne podatke."
                );
            }

            return screening;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Projekcija ne postoji."
            );
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public HoldResponse createHold(ReservationResponse reservation) {
        try {
            inventory.post()
                    .uri("/api/inventory/screenings/{id}/initialize",
                            reservation.screeningId())
                    .retrieve()
                    .toBodilessEntity();

            var hold = inventory.post()
                    .uri("/api/inventory/holds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new HoldRequest(
                            reservation.id(),
                            reservation.screeningId(),
                            reservation.seats()
                    ))
                    .retrieve()
                    .body(HoldResponse.class);

            if (hold == null
                    || !reservation.id().equals(hold.reservationId())
                    || !reservation.screeningId().equals(hold.screeningId())
                    || !"HELD".equals(hold.status())
                    || hold.expiresAt() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Servis sedišta nije vratio očekivano zauzimanje."
                );
            }

            return hold;
        } catch (HttpClientErrorException exception) {
            int code = exception.getStatusCode().value();

            if (code == 400 || code == 404 || code == 409) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Izabrana sedišta nije moguće zauzeti."
                );
            }

            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private RestClient buildClient(String url) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(factory)
                .build();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Zavisni servis nije dostupan. Ponovi zahtev sa istim ID-em rezervacije."
        );
    }
    
    public HoldResponse getHold(UUID reservationId) {
        try {
            var hold = inventory.get()
                    .uri("/api/inventory/holds/{id}", reservationId)
                    .retrieve()
                    .body(HoldResponse.class);

            return validateHold(hold, reservationId);
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public HoldResponse releaseHold(UUID reservationId) {
        try {
            var hold = inventory.post()
                    .uri("/api/inventory/holds/{id}/release", reservationId)
                    .retrieve()
                    .body(HoldResponse.class);

            return validateHold(hold, reservationId);
        } catch (HttpClientErrorException.Conflict exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Zauzimanje je već potvrđeno i ne može se otkazati."
            );
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private HoldResponse validateHold(HoldResponse hold, UUID id) {
        if (hold == null || !id.equals(hold.reservationId())
                || hold.status() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Servis sedišta je vratio neispravan odgovor."
            );
        }

        return hold;
    }
    
    public HoldResponse confirmHold(UUID reservationId) {
        try {
            var hold = inventory.post()
                    .uri("/api/inventory/holds/{id}/confirm", reservationId)
                    .retrieve()
                    .body(HoldResponse.class);

            return validateHold(hold, reservationId);
        } catch (HttpClientErrorException.Conflict exception) {
            // Proveravamo da li je čuvanje sedišta isteklo ili otkazano.
            return getHold(reservationId);
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }
}