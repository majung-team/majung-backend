CREATE TABLE IF NOT EXISTS vehicle_enrollment (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    device_hash VARCHAR(255) NOT NULL UNIQUE,
    code_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimant_id UUID,
    confirmed_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS vehicle_enrollment_expiry ON vehicle_enrollment(expires_at);
