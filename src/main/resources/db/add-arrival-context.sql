-- Additive: old slots and old endpoint-only subscriptions remain compatible.
ALTER TABLE arrival_push ADD COLUMN IF NOT EXISTS arrived_notified_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE arrival_push ADD COLUMN IF NOT EXISTS p256dh VARCHAR(255);
ALTER TABLE arrival_push ADD COLUMN IF NOT EXISTS auth VARCHAR(255);
ALTER TABLE arrival_push ADD COLUMN IF NOT EXISTS target_path VARCHAR(255);
