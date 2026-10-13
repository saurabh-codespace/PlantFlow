CREATE TABLE trip_fact (
    trip_id BIGINT PRIMARY KEY,
    aggregate_version BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    route VARCHAR(512) NOT NULL,
    status VARCHAR(24) NOT NULL,
    passengers INTEGER NOT NULL CHECK (passengers >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    wait_minutes DOUBLE PRECISION,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_trip_fact_created ON trip_fact(created_at);
CREATE TABLE activity (
    id VARCHAR(100) PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor VARCHAR(255) NOT NULL
);
CREATE INDEX ix_activity_occurred ON activity(occurred_at DESC);
CREATE TABLE projection_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    fleet_version BIGINT NOT NULL DEFAULT 0,
    fleet_busy BIGINT NOT NULL DEFAULT 0,
    fleet_available BIGINT NOT NULL DEFAULT 0,
    fleet_maintenance BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE
);
INSERT INTO projection_state(id) VALUES (1);
