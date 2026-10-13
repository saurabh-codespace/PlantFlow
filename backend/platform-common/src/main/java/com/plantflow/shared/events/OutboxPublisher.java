package com.plantflow.shared.events;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="plantflow.messaging.enabled",havingValue="true",matchIfMissing=true)
public class OutboxPublisher {
    private final JdbcClient db;
    private final RabbitTemplate rabbit;
    public OutboxPublisher(JdbcClient db,RabbitTemplate rabbit) { this.db=db;this.rabbit=rabbit; }
    record Pending(String id,String type,String payload) {}
    @Transactional
    public int publishBatch() {
        var events=db.sql("SELECT id,type,payload FROM outbox_event WHERE published_at IS NULL ORDER BY created_at,id LIMIT 20 FOR UPDATE").query(Pending.class).list();
        for(var event:events) {
            var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);props.setContentEncoding("UTF-8");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(event.id());
            var correlation=new CorrelationData(event.id());
            rabbit.send("plantflow.events",event.type(),new Message(event.payload().getBytes(StandardCharsets.UTF_8),props),correlation);
            try {
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("Broker did not route/confirm event " + event.id());
            } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted publishing event",e); }
            catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { throw new IllegalStateException("Event confirmation failed",e); }
            db.sql("UPDATE outbox_event SET published_at=? WHERE id=?").params(Timestamp.from(Instant.now()),event.id()).update();
        }
        return events.size();
    }
}
