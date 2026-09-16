CREATE TABLE inventory_screenings (
    screening_id BIGINT PRIMARY KEY,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_count INTEGER NOT NULL,
    seats_per_row INTEGER NOT NULL,

    CONSTRAINT chk_inventory_rows
        CHECK (row_count BETWEEN 1 AND 26),

    CONSTRAINT chk_inventory_seats_per_row
        CHECK (seats_per_row BETWEEN 1 AND 50)
);

CREATE TABLE seat_holds (
    reservation_id UUID PRIMARY KEY,
    screening_id BIGINT NOT NULL
        REFERENCES inventory_screenings(screening_id),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uq_hold_screening
        UNIQUE (reservation_id, screening_id),

    CONSTRAINT chk_hold_status
        CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),

    CONSTRAINT chk_hold_expiration
        CHECK (expires_at > created_at)
);

CREATE TABLE inventory_seats (
    screening_id BIGINT NOT NULL
        REFERENCES inventory_screenings(screening_id),
    row_number INTEGER NOT NULL,
    seat_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    reservation_id UUID,

    PRIMARY KEY (screening_id, row_number, seat_number),

    CONSTRAINT fk_seat_hold
        FOREIGN KEY (reservation_id, screening_id)
        REFERENCES seat_holds(reservation_id, screening_id),

    CONSTRAINT chk_seat_row
        CHECK (row_number BETWEEN 1 AND 26),

    CONSTRAINT chk_seat_number
        CHECK (seat_number BETWEEN 1 AND 50),

    CONSTRAINT chk_seat_status
        CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),

    CONSTRAINT chk_seat_reservation
        CHECK (
            (status = 'AVAILABLE' AND reservation_id IS NULL)
            OR
            (status IN ('HELD', 'SOLD') AND reservation_id IS NOT NULL)
        )
);

CREATE INDEX idx_hold_expiration
    ON seat_holds (expires_at)
    WHERE status = 'HELD';

CREATE INDEX idx_seat_reservation
    ON inventory_seats (reservation_id)
    WHERE reservation_id IS NOT NULL;