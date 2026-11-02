-- Quick message from passenger to vehicle
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS quick_message VARCHAR(100);
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS quick_message_at TIMESTAMP(6) WITH TIME ZONE;
