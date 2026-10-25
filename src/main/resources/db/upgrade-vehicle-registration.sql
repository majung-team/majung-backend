-- Stop the API, back up the database, run once on the previous schema, then deploy.
ALTER TABLE pickup ADD COLUMN vehicle_id UUID;
CREATE TABLE registered_vehicle (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    credential_hash VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    registered_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE TABLE vehicle_registration_code (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    code_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITH TIME ZONE,
    vehicle_id UUID
);
CREATE INDEX pickup_vehicle ON pickup (vehicle_id);
