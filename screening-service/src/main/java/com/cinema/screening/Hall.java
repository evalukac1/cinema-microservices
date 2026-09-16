package com.cinema.screening;

import jakarta.persistence.*;

@Entity
@Table(name = "halls")
public class Hall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, unique = true, length = 100)
    String name;

    @Column(name = "row_count", nullable = false)
    int rowCount;

    @Column(name = "seats_per_row", nullable = false)
    int seatsPerRow;

    protected Hall() {
    }

    public Hall(String name, int rowCount, int seatsPerRow) {
        this.name = name;
        this.rowCount = rowCount;
        this.seatsPerRow = seatsPerRow;
    }
}