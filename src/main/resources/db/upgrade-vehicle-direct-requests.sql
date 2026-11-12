-- Back up the database; run once against PostgreSQL before deploying feat/direct-vehicle-requests.
ALTER TABLE registered_vehicle ADD COLUMN IF NOT EXISTS vehicle_code VARCHAR(32);
ALTER TABLE registered_vehicle ADD COLUMN IF NOT EXISTS vehicle_model VARCHAR(64);
ALTER TABLE registered_vehicle ADD COLUMN IF NOT EXISTS license_plate VARCHAR(32);
ALTER TABLE registered_vehicle ADD COLUMN IF NOT EXISTS owner_hint VARCHAR(80);
ALTER TABLE registered_vehicle ADD COLUMN IF NOT EXISTS direct_request_enabled BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE registered_vehicle
SET vehicle_code = 'VEH-' || upper(substr(replace(id::text, '-', ''), 1, 6))
WHERE vehicle_code IS NULL;

ALTER TABLE registered_vehicle ALTER COLUMN vehicle_code SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS registered_vehicle_code_uidx ON registered_vehicle(vehicle_code);

ALTER TABLE ride_request ALTER COLUMN driver_id DROP NOT NULL;
CREATE INDEX IF NOT EXISTS ride_request_vehicle ON ride_request(vehicle_id, created_at);

CREATE TABLE IF NOT EXISTS vehicle_passenger_trust (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    vehicle_id UUID NOT NULL REFERENCES registered_vehicle(id) ON DELETE CASCADE,
    passenger_id UUID NOT NULL REFERENCES member_account(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    UNIQUE (vehicle_id, passenger_id)
);
CREATE INDEX IF NOT EXISTS vehicle_passenger_trust_passenger ON vehicle_passenger_trust(passenger_id, status, updated_at);
