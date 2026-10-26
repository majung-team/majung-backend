CREATE TABLE arrival_push (
    pickup_id UUID PRIMARY KEY,
    endpoint VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    notified_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX arrival_push_created ON arrival_push(created_at);
