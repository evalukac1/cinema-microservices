package com.cinema.screening;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.screening.ApiModels.CreateScreeningRequest;
import com.cinema.screening.ApiModels.ScreeningResponse;

@Service
public class ScreeningService {

    private final ScreeningRepository screenings;
    private final HallRepository halls;
    private final MovieClient movieClient;
    private final TransactionTemplate transaction;

    public ScreeningService(
            ScreeningRepository screenings,
            HallRepository halls,
            MovieClient movieClient,
            PlatformTransactionManager transactionManager) {
        this.screenings = screenings;
        this.halls = halls;
        this.movieClient = movieClient;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<ScreeningResponse> getAll() {
        return screenings.findAll(Sort.by("startsAt", "id")).stream()
                .map(this::toResponse)
                .toList();
    }

    public ScreeningResponse getById(Long id) {
        return screenings.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Projekcija ne postoji."
                ));
    }

    public ScreeningResponse create(CreateScreeningRequest request) {
        if (!halls.existsById(request.hallId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Sala ne postoji."
            );
        }

        var movie = movieClient.getMovie(request.movieId());
        var start = request.startsAt().toInstant();
        var end = start.plus(movie.durationMinutes(), ChronoUnit.MINUTES);

        return Objects.requireNonNull(transaction.execute(status -> {
            var screening = new Screening(
                    request.movieId(),
                    request.hallId(),
                    start,
                    end,
                    request.ticketPrice(),
                    request.currency()
            );

            return toResponse(screenings.saveAndFlush(screening));
        }));
    }

    private ScreeningResponse toResponse(Screening screening) {
        return new ScreeningResponse(
                screening.id,
                screening.movieId,
                screening.hallId,
                screening.startsAt,
                screening.endsAt,
                screening.ticketPrice,
                screening.currency
        );
    }
}