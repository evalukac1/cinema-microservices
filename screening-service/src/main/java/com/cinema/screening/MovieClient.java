package com.cinema.screening;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.screening.ApiModels.MovieResponse;

@Component
public class MovieClient {

    private final RestClient client;

    public MovieClient(
            @Value("${clients.movie-service.url}") String baseUrl) {

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public MovieResponse getMovie(Long id) {
        try {
            var movie = client.get()
                    .uri("/api/movies/{id}", id)
                    .retrieve()
                    .body(MovieResponse.class);

            if (movie == null || !id.equals(movie.id())
                    || movie.durationMinutes() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Servis za filmove je vratio neispravne podatke."
                );
            }

            return movie;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Film ne postoji."
            );
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Servis za filmove trenutno nije dostupan."
            );
        }
    }
}