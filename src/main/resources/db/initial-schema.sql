-- Run once against a new, empty PostgreSQL database before starting the deploy profile.
-- Existing local H2 data is not migrated by this file.
CREATE TABLE pickup (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    vehicle_id UUID,
    owner_hash VARCHAR(255),
    invite_hash VARCHAR(255),
    invite_expires_at TIMESTAMP(6) WITH TIME ZONE,
    expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    ended_at TIMESTAMP(6) WITH TIME ZONE,
    status VARCHAR(255),
    place_label VARCHAR(255),
    place_latitude DOUBLE PRECISION NOT NULL,
    place_longitude DOUBLE PRECISION NOT NULL,
    participant_id UUID,
    participant_name VARCHAR(255),
    participant_hash VARCHAR(255),
    readiness VARCHAR(255),
    readiness_sequence BIGINT NOT NULL,
    vehicle_sequence BIGINT NOT NULL,
    vehicle_latitude DOUBLE PRECISION,
    vehicle_longitude DOUBLE PRECISION,
    duration_seconds BIGINT,
    vehicle_source_time TIMESTAMP(6) WITH TIME ZONE,
    vehicle_received_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX pickup_retention_ended ON pickup (ended_at);
CREATE INDEX pickup_retention_expires ON pickup (expires_at);

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

CREATE TABLE member_account (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    provider VARCHAR(255) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    majung_id VARCHAR(255) NOT NULL UNIQUE,
    nickname VARCHAR(255) NOT NULL,
    normalized_nickname VARCHAR(255) NOT NULL,
    profile_ready BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    UNIQUE (provider, provider_subject)
);
CREATE INDEX member_nickname ON member_account (normalized_nickname);
CREATE TABLE member_connection (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    first_member UUID NOT NULL REFERENCES member_account(id),
    second_member UUID NOT NULL REFERENCES member_account(id),
    requester UUID NOT NULL REFERENCES member_account(id),
    recipient UUID NOT NULL REFERENCES member_account(id),
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    UNIQUE (first_member, second_member)
);
CREATE INDEX connection_requester ON member_connection (requester, status);
CREATE INDEX connection_recipient ON member_connection (recipient, status);

CREATE TABLE kakao_unlink (
    provider_subject VARCHAR(30) PRIMARY KEY,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE vehicle_account_link (
    vehicle_id UUID PRIMARY KEY REFERENCES registered_vehicle(id),
    version BIGINT NOT NULL,
    member_id UUID NOT NULL REFERENCES member_account(id) ON DELETE CASCADE,
    linked_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX vehicle_account_member ON vehicle_account_link(member_id);
CREATE TABLE vehicle_account_challenge (
    vehicle_id UUID PRIMARY KEY REFERENCES registered_vehicle(id),
    version BIGINT NOT NULL,
    challenge_id UUID NOT NULL UNIQUE,
    code_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimant_id UUID REFERENCES member_account(id) ON DELETE CASCADE,
    confirmed_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX vehicle_account_expiry ON vehicle_account_challenge(expires_at);

CREATE TABLE ride_request (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    driver_id UUID NOT NULL REFERENCES member_account(id) ON DELETE CASCADE,
    passenger_id UUID NOT NULL REFERENCES member_account(id) ON DELETE CASCADE,
    vehicle_id UUID REFERENCES registered_vehicle(id),
    pickup_id UUID,
    status VARCHAR(255) NOT NULL,
    place_label VARCHAR(255) NOT NULL,
    place_latitude DOUBLE PRECISION NOT NULL,
    place_longitude DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    queue_order BIGINT,
    driver_invitation BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ride_request_driver ON ride_request(driver_id, created_at);
CREATE INDEX ride_request_passenger ON ride_request(passenger_id, created_at);
