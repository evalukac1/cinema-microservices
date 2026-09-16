CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    screening_id BIGINT NOT NULL,
    customer_email VARCHAR(254) NOT NULL,
    status VARCHAR(30) NOT NULL,

    unit_price NUMERIC(10, 2) NOT NULL,
    total_price NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    seat_count INTEGER NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_reservation_screening
        CHECK (screening_id > 0),

    CONSTRAINT chk_reservation_status
        CHECK (status IN (
            'CREATING',
            'AWAITING_PAYMENT',
            'PAYMENT_PENDING',
            'CONFIRMING',
            'CONFIRMED',
            'CANCEL_PENDING',
            'CANCELLED',
            'EXPIRED',
            'REJECTED'
        )),

    CONSTRAINT chk_reservation_seat_count
        CHECK (seat_count BETWEEN 1 AND 10),

    CONSTRAINT chk_reservation_price
        CHECK (
            unit_price > 0
            AND total_price = unit_price * seat_count
        ),

    CONSTRAINT chk_reservation_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    row_number INTEGER NOT NULL,
    seat_number INTEGER NOT NULL,

    PRIMARY KEY (reservation_id, row_number, seat_number),

    CONSTRAINT chk_reservation_seat_row
        CHECK (row_number BETWEEN 1 AND 26),

    CONSTRAINT chk_reservation_seat_number
        CHECK (seat_number BETWEEN 1 AND 50)
);

CREATE INDEX idx_reservations_status_updated
    ON reservations (status, updated_at);

CREATE INDEX idx_reservations_expiration
    ON reservations (expires_at)
    WHERE status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING');

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES reservations(id),
    event_type VARCHAR(100) NOT NULL,
    routing_key VARCHAR(150) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT chk_outbox_attempts
        CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);