package com.cinema.screening;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "screenings")
public class Screening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "movie_id", nullable = false)
    Long movieId;

    @Column(name = "hall_id", nullable = false)
    Long hallId;

    @Column(name = "starts_at", nullable = false)
    Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    Instant endsAt;

    @Column(name = "ticket_price", nullable = false, precision = 10, scale = 2)
    BigDecimal ticketPrice;

    @Column(nullable = false, length = 3)
    String currency;

    protected Screening() {
    }

    public Screening(Long movieId, Long hallId, Instant startsAt,
                     Instant endsAt, BigDecimal ticketPrice, String currency) {
        this.movieId = movieId;
        this.hallId = hallId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.ticketPrice = ticketPrice;
        this.currency = currency;
    }
}