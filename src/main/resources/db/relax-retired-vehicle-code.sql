-- Existing databases only: retain old codes but stop requiring them for new vehicles.
-- Check that vehicle_code exists before applying; new schemas do not contain it.
ALTER TABLE registered_vehicle ALTER COLUMN vehicle_code DROP NOT NULL;
