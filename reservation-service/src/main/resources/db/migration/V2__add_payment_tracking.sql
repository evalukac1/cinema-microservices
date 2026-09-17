ALTER TABLE reservations
    ADD COLUMN payment_id UUID;

CREATE UNIQUE INDEX uq_reservation_payment_id
    ON reservations (payment_id)
    WHERE payment_id IS NOT NULL;

ALTER TABLE reservations
    DROP CONSTRAINT chk_reservation_status;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservation_status
    CHECK (status IN (
        'CREATING',
        'AWAITING_PAYMENT',
        'PAYMENT_PENDING',
        'CONFIRMING',
        'CONFIRMED',
        'CANCEL_PENDING',
        'CANCELLED',
        'EXPIRED',
        'REJECTED',
        'REFUND_PENDING',
        'REFUNDED'
    ));