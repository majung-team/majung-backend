-- Old clients remain compatible. A pickup has no proposal until someone suggests another place.
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS proposal_by VARCHAR(255);
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS proposal_label VARCHAR(255);
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS proposal_latitude DOUBLE PRECISION;
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS proposal_longitude DOUBLE PRECISION;
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS proposal_at TIMESTAMP(6) WITH TIME ZONE;
