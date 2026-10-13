CREATE TABLE outbox_event (
 id VARCHAR(36) PRIMARY KEY, type VARCHAR(80) NOT NULL, payload TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX outbox_pending ON outbox_event(published_at,created_at);
CREATE TABLE inbox_event (event_id VARCHAR(36) PRIMARY KEY, received_at TIMESTAMP WITH TIME ZONE NOT NULL);
