-- Apply once, inside a transaction, to an existing database before deployment.
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
