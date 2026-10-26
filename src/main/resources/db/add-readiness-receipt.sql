-- Old clients remain compatible. Existing readiness is unconfirmed until a vehicle receives it.
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS readiness_received_sequence BIGINT NOT NULL DEFAULT 0;
