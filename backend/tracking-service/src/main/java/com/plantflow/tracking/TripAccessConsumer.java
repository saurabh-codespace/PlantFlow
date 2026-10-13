package com.plantflow.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.events.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TripAccessConsumer {
    private final JdbcClient db;private final Inbox inbox;private final ObjectMapper json;
    public TripAccessConsumer(JdbcClient db,Inbox inbox,ObjectMapper json) { this.db=db;this.inbox=inbox;this.json=json; }
    @Transactional(rollbackFor=Exception.class)
    public void accept(String raw) throws Exception {
        var event=json.readValue(raw,EventEnvelope.class);
        if(!event.type().equals("transport.trip-changed")) throw new IllegalArgumentException("Unexpected tracking event");
        db.sql("SELECT id FROM tracking_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        if(!inbox.first(event)) return;
        var trip=event.payload().path("trip");long id=trip.path("id").asLong();
        if(id<=0 || !Long.toString(id).equals(event.aggregateId()) || event.aggregateVersion()<1 || !trip.hasNonNull("status") || !event.payload().path("authorizedRiders").isArray()) throw new IllegalArgumentException("Invalid tracking aggregate");
        if(!java.util.Set.of("REQUESTED","ASSIGNING","ASSIGNED","ARRIVED","IN_PROGRESS","COMPLETED","CANCELLED").contains(trip.path("status").asText())) throw new IllegalArgumentException("Invalid trip state");
        var previous=db.sql("SELECT version FROM tracked_trip WHERE trip_id=?").param(id).query(Long.class).optional();
        if(previous.isPresent()&&previous.get()>=event.aggregateVersion()) return;
        var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("version",event.aggregateVersion())
            .addValue("status",trip.path("status").asText()).addValue("driver",trip.hasNonNull("driverId")?trip.path("driverId").asText():null)
            .addValue("vehicle",trip.hasNonNull("vehicleId")?trip.path("vehicleId").asLong():null,java.sql.Types.BIGINT)
            .addValue("label",trip.hasNonNull("vehicleLabel")?trip.path("vehicleLabel").asText():null);
        if(previous.isEmpty()) db.sql("INSERT INTO tracked_trip(trip_id,version,status,driver_id,vehicle_id,vehicle_label) VALUES (:id,:version,:status,:driver,:vehicle,:label)").paramSource(params).update();
        else db.sql("UPDATE tracked_trip SET version=:version,status=:status,driver_id=:driver,vehicle_id=:vehicle,vehicle_label=:label WHERE trip_id=:id").paramSource(params).update();
        db.sql("DELETE FROM trip_rider WHERE trip_id=?").param(id).update();
        for(var rider:event.payload().path("authorizedRiders")) db.sql("INSERT INTO trip_rider(trip_id,username) VALUES (?,?)").params(id,rider.asText()).update();
        if(!java.util.Set.of("ASSIGNED","ARRIVED","IN_PROGRESS").contains(trip.path("status").asText())) db.sql("DELETE FROM latest_position WHERE trip_id=?").param(id).update();
        else db.sql("DELETE FROM latest_position WHERE trip_id=? AND subject='RIDER' AND username NOT IN (SELECT username FROM trip_rider WHERE trip_id=?)").params(id,id).update();
    }
    @Component
    @ConditionalOnProperty(name="plantflow.messaging.enabled",havingValue="true",matchIfMissing=true)
    public static class Listener {
        private final TripAccessConsumer consumer;public Listener(TripAccessConsumer consumer) { this.consumer=consumer; }
        @RabbitListener(queues="plantflow.tracking") public void listen(String raw) throws Exception { consumer.accept(raw); }
    }
}
