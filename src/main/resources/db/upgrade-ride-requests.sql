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
    queue_order BIGINT
);
CREATE INDEX ride_request_driver ON ride_request(driver_id, created_at);
CREATE INDEX ride_request_passenger ON ride_request(passenger_id, created_at);
