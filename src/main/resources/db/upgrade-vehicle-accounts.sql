-- Back up the database; apply before deploying this feature. Do not rerun on an initialized schema.
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
