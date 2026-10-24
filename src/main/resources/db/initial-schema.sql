-- Run once against a new, empty PostgreSQL database before starting the deploy profile.
-- Existing local H2 data is not migrated by this file.
CREATE TABLE pickup (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
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
