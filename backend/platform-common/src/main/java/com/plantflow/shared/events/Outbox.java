package com.plantflow.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;
    public Outbox(JdbcClient db,ObjectMapper json) { this.db=db;this.json=json; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(String type,String aggregateId,long version,String actor,Object payload) {
        var event=new EventEnvelope(UUID.randomUUID().toString(),type,1,aggregateId,version,Instant.now(),actor,json.valueToTree(payload));
        try { db.sql("INSERT INTO outbox_event(id,type,payload,created_at) VALUES (?,?,?,?)")
            .params(event.id(),type,json.writeValueAsString(event),Timestamp.from(event.occurredAt())).update(); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Event serialization failed",e); }
    }
}
