CREATE TABLE tracking_guard (id INTEGER PRIMARY KEY);
INSERT INTO tracking_guard(id) VALUES(1);
CREATE TABLE tracked_trip (
 trip_id BIGINT PRIMARY KEY,version BIGINT NOT NULL,status VARCHAR(20) NOT NULL,
 driver_id VARCHAR(100),vehicle_id BIGINT,vehicle_label VARCHAR(30)
);
CREATE TABLE trip_rider (trip_id BIGINT NOT NULL REFERENCES tracked_trip(trip_id),username VARCHAR(100) NOT NULL,PRIMARY KEY(trip_id,username));
CREATE TABLE latest_position (
 trip_id BIGINT NOT NULL REFERENCES tracked_trip(trip_id),subject VARCHAR(20) NOT NULL,username VARCHAR(100) NOT NULL,
 latitude DOUBLE PRECISION NOT NULL,longitude DOUBLE PRECISION NOT NULL,accuracy DOUBLE PRECISION NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(trip_id,subject,username)
);
