ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

ALTER TABLE reservations
    DROP CONSTRAINT IF EXISTS chk_reservations_status;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_status
        CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED'));