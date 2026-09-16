package com.cinema.screening;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.cinema.screening.ApiModels.CreateHallRequest;
import com.cinema.screening.ApiModels.HallResponse;

@Service
@Transactional(readOnly = true)
public class HallService {

    private final HallRepository repository;

    public HallService(HallRepository repository) {
        this.repository = repository;
    }

    public List<HallResponse> getAll() {
        return repository.findAll(Sort.by("id")).stream()
                .map(this::toResponse)
                .toList();
    }

    public HallResponse getById(Long id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sala ne postoji."
                ));
    }

    @Transactional
    public HallResponse create(CreateHallRequest request) {
        var hall = new Hall(
                request.name().strip(),
                request.rowCount(),
                request.seatsPerRow()
        );

        return toResponse(repository.saveAndFlush(hall));
    }

    private HallResponse toResponse(Hall hall) {
        return new HallResponse(
                hall.id, hall.name, hall.rowCount, hall.seatsPerRow
        );
    }
}