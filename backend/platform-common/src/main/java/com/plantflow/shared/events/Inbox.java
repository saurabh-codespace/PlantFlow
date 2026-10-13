package com.plantflow.shared.events;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Inbox {
    private final JdbcClient db;
    public Inbox(JdbcClient db) { this.db=db; }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean first(EventEnvelope event) {
        if(event.schemaVersion()!=1 || event.id()==null || event.id().length()!=36 || event.aggregateId()==null || event.aggregateVersion()<1 || event.occurredAt()==null || event.actor()==null || event.payload()==null || !event.payload().isObject())
            throw new IllegalArgumentException("Unsupported event envelope");
        if(db.sql("SELECT COUNT(*) FROM inbox_event WHERE event_id=?").param(event.id()).query(Long.class).single()>0) return false;
        // A concurrent duplicate rolls back on the PK; retry then sees the committed inbox row.
        db.sql("INSERT INTO inbox_event(event_id,received_at) VALUES (?,?)").params(event.id(),Timestamp.from(Instant.now())).update();
        return true;
    }
}
