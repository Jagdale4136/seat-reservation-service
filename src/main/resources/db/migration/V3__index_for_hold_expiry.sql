CREATE INDEX IF NOT EXISTS idx_reservations_held_expires_at
    ON reservations(expires_at)
    WHERE status = 'HELD';