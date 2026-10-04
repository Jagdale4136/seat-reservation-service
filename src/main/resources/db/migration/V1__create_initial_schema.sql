-- ============================================================
-- SHOWS
-- ============================================================

CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price_paise BIGINT NOT NULL,
    per_user_limit INTEGER NOT NULL DEFAULT 4,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_shows_price_paise
        CHECK (price_paise >= 0),

    CONSTRAINT chk_shows_per_user_limit
        CHECK (per_user_limit > 0)
);


-- ============================================================
-- RESERVATIONS
-- ============================================================

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    amount_paise BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMPTZ,

    CONSTRAINT fk_reservations_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id),

    CONSTRAINT chk_reservations_amount
        CHECK (amount_paise >= 0),

    CONSTRAINT chk_reservations_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);


CREATE INDEX idx_reservations_show_user
    ON reservations(show_id, user_id);

CREATE INDEX idx_reservations_show_status
    ON reservations(show_id, status);


-- ============================================================
-- SEATS
-- ============================================================

CREATE TABLE seats (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    seat_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    current_reservation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_seats_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_seats_current_reservation
        FOREIGN KEY (current_reservation_id)
        REFERENCES reservations(id),

    CONSTRAINT chk_seats_status
        CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),

    CONSTRAINT uq_seats_show_seat_number
        UNIQUE (show_id, seat_number)
);


CREATE INDEX idx_seats_show_status
    ON seats(show_id, status);

CREATE INDEX idx_seats_show_seat_number
    ON seats(show_id, seat_number);


-- ============================================================
-- RESERVATION ↔ SEAT
-- ============================================================

CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL,
    seat_id UUID NOT NULL,

    PRIMARY KEY (reservation_id, seat_id),

    CONSTRAINT fk_reservation_seats_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_reservation_seats_seat
        FOREIGN KEY (seat_id)
        REFERENCES seats(id)
);


CREATE INDEX idx_reservation_seats_seat
    ON reservation_seats(seat_id);


-- ============================================================
-- IDEMPOTENCY KEYS
-- ============================================================

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_idempotency_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_idempotency_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id),

    CONSTRAINT uq_idempotency_user_show_key
        UNIQUE (show_id, user_id, idempotency_key)
);


CREATE INDEX idx_idempotency_reservation
    ON idempotency_keys(reservation_id);


-- ============================================================
-- USER + SHOW BOOKING LIMIT
-- ============================================================

CREATE TABLE user_show_limits (
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    active_seat_count INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (show_id, user_id),

    CONSTRAINT fk_user_show_limits_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_user_show_limits_count
        CHECK (active_seat_count >= 0)
);