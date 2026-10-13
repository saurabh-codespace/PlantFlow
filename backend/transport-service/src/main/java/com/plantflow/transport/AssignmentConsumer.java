package com.plantflow.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.events.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AssignmentConsumer {
    private final JdbcClient db;private final Inbox inbox;private final ObjectMapper json;private final TripRepository trips;private final TripService service;
    public AssignmentConsumer(JdbcClient db,Inbox inbox,ObjectMapper json,TripRepository trips,TripService service) { this.db=db;this.inbox=inbox;this.json=json;this.trips=trips;this.service=service; }
    @Transactional(rollbackFor=Exception.class)
    public void accept(String raw) throws Exception {
        var event=json.readValue(raw,EventEnvelope.class);
        if(!java.util.Set.of("fleet.assignment-confirmed","fleet.assignment-rejected").contains(event.type())) throw new IllegalArgumentException("Unexpected assignment event");
        long id=event.payload().path("tripId").asLong();
        if(id<=0 || !Long.toString(id).equals(event.aggregateId()) || event.aggregateVersion()<1) throw new IllegalArgumentException("Invalid assignment aggregate");
        Trip trip=trips.lock(id,"system");
        if(!inbox.first(event)) return;
        Long pending=db.sql("SELECT assignment_version FROM trip WHERE id=?").param(id).query(Long.class).optional().orElse(null);
        if(!trip.status().equals("ASSIGNING") || pending==null || pending!=event.aggregateVersion()) return;
        var p=event.payload();
        if(event.type().equals("fleet.assignment-confirmed")) {
            if(!p.hasNonNull("driverId") || p.path("driverId").asText().isBlank() || !p.hasNonNull("driverName") || !p.hasNonNull("vehicleLabel") || p.path("vehicleId").asLong()<=0 || !p.hasNonNull("capacity") || p.path("capacity").asInt()<Math.max(1,trip.passengers()) || p.path("capacity").asInt()>60) throw new IllegalArgumentException("Invalid fleet confirmation");
            db.sql("UPDATE trip SET status='ASSIGNED',vehicle_id=?,vehicle_label=?,capacity=?,driver_id=?,driver_name=?,assigned_at=? WHERE id=?")
                .params(p.path("vehicleId").asLong(),p.path("vehicleLabel").asText(),p.path("capacity").asInt(),p.path("driverId").asText(),p.path("driverName").asText(),Timestamp.from(Instant.now()),id).update();
            service.changed(id,"TRIP_ASSIGNED",event.actor());
        } else {
            String reason=p.path("reason").asText("Fleet could not accept the assignment.");
            db.sql("UPDATE trip SET status=?,assignment_error=? WHERE id=?").params(trip.kind().equals("SHUTTLE")?"CANCELLED":"REQUESTED",reason.substring(0,Math.min(reason.length(),300)),id).update();
            service.changed(id,"ASSIGNMENT_REJECTED",event.actor());
        }
    }
    @Component
    @ConditionalOnProperty(name="plantflow.messaging.enabled",havingValue="true",matchIfMissing=true)
    public static class Listener {
        private final AssignmentConsumer consumer;public Listener(AssignmentConsumer consumer) { this.consumer=consumer; }
        @RabbitListener(queues="plantflow.transport") public void listen(String raw) throws Exception { consumer.accept(raw); }
    }
}
