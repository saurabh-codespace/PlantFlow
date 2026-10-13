package com.plantflow.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.events.EventEnvelope;
import com.plantflow.shared.events.Inbox;
import com.plantflow.shared.events.Outbox;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetEvents {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Inbox inbox;
    private final Outbox outbox;
    private final FleetService fleet;
    public FleetEvents(JdbcClient db, ObjectMapper json, Inbox inbox, Outbox outbox, FleetService fleet) {
        this.db=db; this.json=json; this.inbox=inbox; this.outbox=outbox; this.fleet=fleet;
    }
    record Assignment(long tripId, long vehicleId, String driverId, int passengers, String kind) {}
    record State(long tripId, long requestVersion, long terminalVersion, String decision, String reason) {}
    record Allocation(long tripId, long vehicleId, String driverId) {}

    @Transactional(rollbackFor=Exception.class)
    @RabbitListener(queues="plantflow.fleet", autoStartup="${plantflow.messaging.enabled:true}")
    public void receive(String raw) throws JsonProcessingException {
        EventEnvelope event = json.readValue(raw,EventEnvelope.class);
        if (event.schemaVersion()!=1 || event.aggregateVersion()<1 || event.id()==null || event.payload()==null)
            throw new IllegalArgumentException("Unsupported fleet event envelope");
        if (!event.type().equals("transport.assignment-requested") && !event.type().equals("transport.trip-changed"))
            throw new IllegalArgumentException("Unsupported fleet event type");
        fleet.lockFleet();
        if (!inbox.first(event)) return;
        if (event.type().equals("transport.assignment-requested")) assign(event);
        else release(event);
    }

    private State state(long tripId) {
        var existing=db.sql("SELECT * FROM trip_assignment WHERE trip_id=?").param(tripId).query(State.class).optional();
        if (existing.isPresent()) return existing.get();
        db.sql("INSERT INTO trip_assignment(trip_id) VALUES (?)").param(tripId).update();
        return new State(tripId,0,0,null,null);
    }

    private void assign(EventEnvelope event) throws JsonProcessingException {
        Assignment request=json.treeToValue(event.payload(),Assignment.class);
        if (request.tripId()<1 || !event.aggregateId().equals(Long.toString(request.tripId())))
            throw new IllegalArgumentException("Assignment aggregate does not match trip");
        State state=state(request.tripId());
        if (event.aggregateVersion()<state.requestVersion()) return;
        if (state.terminalVersion()>0) { reject(event,request.tripId(),"Trip has already finished or been cancelled."); return; }
        if (event.aggregateVersion()==state.requestVersion() && "REJECTED".equals(state.decision())) {
            reject(event,request.tripId(),state.reason()); return;
        }
        var previous=db.sql("SELECT * FROM allocation WHERE trip_id=?").param(request.tripId()).query(Allocation.class).optional();
        if (previous.isPresent()) {
            var allocation=previous.get();
            if (allocation.vehicleId()!=request.vehicleId() || !allocation.driverId().equals(request.driverId())) {
                reject(event,request.tripId(),"Trip already has a different assignment."); return;
            }
            confirm(event,request); return;
        }
        if (request.vehicleId()<1 || request.driverId()==null || request.driverId().length()>100 || request.passengers()<0 ||
            request.passengers()>60 || !("ON_DEMAND".equals(request.kind()) || "SHUTTLE".equals(request.kind())) ||
            ("ON_DEMAND".equals(request.kind()) && request.passengers()<1)) {
            reject(event,request.tripId(),"Invalid assignment details."); return;
        }
        var vehicle=db.sql("SELECT v.*, false AS busy FROM vehicle v WHERE id=? FOR UPDATE").param(request.vehicleId()).query(FleetService.Vehicle.class).optional();
        if (vehicle.isEmpty()) { reject(event,request.tripId(),"Vehicle does not exist."); return; }
        if (!vehicle.get().status().equals("AVAILABLE")) { reject(event,request.tripId(),"Vehicle is in maintenance."); return; }
        if (vehicle.get().capacity()<request.passengers()) { reject(event,request.tripId(),"Vehicle does not have enough seats."); return; }
        var driver=db.sql("SELECT username FROM driver WHERE username=? FOR UPDATE").param(request.driverId()).query(String.class).optional();
        if (driver.isEmpty()) { reject(event,request.tripId(),"Driver is not registered."); return; }
        if (db.sql("SELECT COUNT(*) FROM allocation WHERE vehicle_id=? OR driver_id=?").params(request.vehicleId(),request.driverId()).query(Long.class).single()>0) {
            reject(event,request.tripId(),"Vehicle or driver already has an active assignment."); return;
        }
        db.sql("INSERT INTO allocation(trip_id,vehicle_id,driver_id) VALUES (?,?,?)").params(request.tripId(),request.vehicleId(),request.driverId()).update();
        confirm(event,request);
        fleet.availability(event.actor());
    }

    private void confirm(EventEnvelope event, Assignment request) {
        var vehicle=db.sql("SELECT v.*, true AS busy FROM vehicle v WHERE id=?").param(request.vehicleId()).query(FleetService.Vehicle.class).single();
        String driverName=db.sql("SELECT name FROM driver WHERE username=?").param(request.driverId()).query(String.class).single();
        db.sql("UPDATE trip_assignment SET request_version=?, decision='CONFIRMED', reason=NULL WHERE trip_id=?")
            .params(event.aggregateVersion(),request.tripId()).update();
        outbox.publish("fleet.assignment-confirmed",event.aggregateId(),event.aggregateVersion(),event.actor(),Map.of(
            "tripId",request.tripId(),"vehicleId",vehicle.id(),"vehicleLabel",vehicle.code(),"capacity",vehicle.capacity(),
            "driverId",request.driverId(),"driverName",driverName));
    }

    private void reject(EventEnvelope event, long tripId, String reason) {
        db.sql("UPDATE trip_assignment SET request_version=?, decision='REJECTED', reason=? WHERE trip_id=?")
            .params(event.aggregateVersion(),reason,tripId).update();
        outbox.publish("fleet.assignment-rejected",event.aggregateId(),event.aggregateVersion(),event.actor(),Map.of("tripId",tripId,"reason",reason));
    }

    private void release(EventEnvelope event) {
        var trip=event.payload().path("trip");
        String status=trip.path("status").asText();
        if (!status.equals("COMPLETED") && !status.equals("CANCELLED")) return;
        long tripId=trip.path("id").asLong();
        if (tripId<1 || !event.aggregateId().equals(Long.toString(tripId))) throw new IllegalArgumentException("Invalid terminal trip");
        State state=state(tripId);
        if (event.aggregateVersion()<=state.terminalVersion() || event.aggregateVersion()<state.requestVersion()) return;
        db.sql("UPDATE trip_assignment SET terminal_version=? WHERE trip_id=?").params(event.aggregateVersion(),tripId).update();
        var allocation=db.sql("SELECT * FROM allocation WHERE trip_id=?").param(tripId).query(Allocation.class).optional();
        if (allocation.isEmpty()) return;
        db.sql("SELECT id FROM vehicle WHERE id=? FOR UPDATE").param(allocation.get().vehicleId()).query(Long.class).single();
        db.sql("SELECT username FROM driver WHERE username=? FOR UPDATE").param(allocation.get().driverId()).query(String.class).single();
        db.sql("DELETE FROM allocation WHERE trip_id=?").param(tripId).update();
        fleet.availability(event.actor());
    }
}
