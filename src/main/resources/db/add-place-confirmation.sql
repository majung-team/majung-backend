-- Apply after add-place-proposal.sql. Existing places begin at version 1.
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS place_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS proposal_id UUID;
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS confirmed_proposal_id UUID;
ALTER TABLE arrival_push ADD COLUMN IF NOT EXISTS place_version BIGINT NOT NULL DEFAULT 1;
