ALTER TABLE pickup ADD COLUMN IF NOT EXISTS arrived_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE pickup ADD COLUMN IF NOT EXISTS last_vehicle_activity_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE ride_request ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP(6) WITH TIME ZONE;
-- Existing rows have no arrival event time: begin a new grace period at migration, never invent an SDK event.
UPDATE pickup SET arrived_at = CURRENT_TIMESTAMP WHERE status = 'ARRIVED' AND arrived_at IS NULL;
UPDATE pickup SET last_vehicle_activity_at = COALESCE(vehicle_received_at, CURRENT_TIMESTAMP)
WHERE status = 'ACTIVE' AND last_vehicle_activity_at IS NULL;
