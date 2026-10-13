package com.plantflow.transport;

import com.plantflow.shared.events.Outbox;
import com.plantflow.shared.security.Actor;
import com.plantflow.shared.ApiException;
import jakarta.validation.constraints.*;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TripService {
    public record Request(@Positive long originId,@Positive long destinationId,@Min(1) @Max(40) int seats,@NotBlank @Size(max=200) String purpose) {}
    public record Shuttle(@Positive long originId,@Positive long destinationId,@NotNull @Future Instant scheduledAt,@Positive long vehicleId,@NotBlank @Size(max=100) String driverId) {}
    public record Assignment(@Positive long vehicleId,@NotBlank @Size(max=100) String driverId) {}
    public record Seats(@Min(1) @Max(40) int seats) {}
    public record Status(@NotNull @Pattern(regexp="ARRIVED|IN_PROGRESS|COMPLETED|CANCELLED") String status) {}
    private final JdbcClient db;
    private final JdbcTemplate jdbc;
    private final TripRepository trips;
    private final Outbox events;
    public TripService(JdbcClient db,JdbcTemplate jdbc,TripRepository trips,Outbox events) { this.db=db;this.jdbc=jdbc;this.trips=trips;this.events=events; }
    public Trip request(Request input,Actor actor) {
        actor.requireRider();validateRoute(input.originId(),input.destinationId());
        long id=insert("ON_DEMAND",input.originId(),input.destinationId(),input.purpose().trim(),actor.username(),null);
        db.sql("INSERT INTO reservation(trip_id,username,seats) VALUES (?,?,?)").params(id,actor.username(),input.seats()).update();
        return changed(id,"TRIP_REQUESTED",actor.username());
    }
    public Trip shuttle(Shuttle input,Actor actor) {
        actor.requireDispatch();validateRoute(input.originId(),input.destinationId());
        if(!input.scheduledAt().isAfter(Instant.now())) throw ApiException.conflict("Departure must be in the future.");
        long id=insert("SHUTTLE",input.originId(),input.destinationId(),"Shift shuttle",actor.username(),input.scheduledAt());
        return requestAssignment(id,new Assignment(input.vehicleId(),input.driverId()),actor,"SHUTTLE_SCHEDULED");
    }
    public Trip assign(long id,Assignment input,Actor actor) {
        actor.requireDispatch();Trip trip=trips.lock(id,actor.username());
        if(!trip.status().equals("REQUESTED")) throw ApiException.conflict("Only requested trips can be assigned.");
        return requestAssignment(id,input,actor,"ASSIGNMENT_REQUESTED");
    }
    private Trip requestAssignment(long id,Assignment input,Actor actor,String eventType) {
        db.sql("UPDATE trip SET status='ASSIGNING',assignment_error=NULL WHERE id=?").param(id).update();
        Trip trip=changed(id,eventType,actor.username());
        long version=db.sql("SELECT version FROM trip WHERE id=?").param(id).query(Long.class).single();
        db.sql("UPDATE trip SET assignment_version=? WHERE id=?").params(version,id).update();
        events.publish("transport.assignment-requested",Long.toString(id),version,actor.username(),Map.of("tripId",id,"vehicleId",input.vehicleId(),"driverId",input.driverId(),"passengers",trip.passengers(),"kind",trip.kind()));
        return trip;
    }
    public Trip reserve(long id,Seats input,Actor actor) {
        actor.requireRider();Trip trip=trips.lock(id,actor.username());checkReservationWindow(trip);
        if(trip.myBookingId()!=null) throw ApiException.conflict("You already have a reservation for this shuttle.");
        if(trip.capacity()==null || trip.passengers()+input.seats()>trip.capacity()) throw ApiException.conflict("There are not enough seats left.");
        db.sql("INSERT INTO reservation(trip_id,username,seats) VALUES (?,?,?)").params(id,actor.username(),input.seats()).update();
        return changed(id,"SEATS_RESERVED",actor.username());
    }
    public Trip cancelReservation(long id,Actor actor) {
        actor.requireRider();Trip trip=trips.lock(id,actor.username());checkReservationWindow(trip);
        if(trip.myBookingId()==null) throw ApiException.missing("Reservation");
        db.sql("DELETE FROM reservation WHERE trip_id=? AND username=?").params(id,actor.username()).update();
        return changed(id,"RESERVATION_CANCELLED",actor.username());
    }
    private void checkReservationWindow(Trip trip) {
        if(!trip.kind().equals("SHUTTLE") || !trip.status().equals("ASSIGNED") || !trip.scheduledAt().isAfter(Instant.now()))
            throw ApiException.conflict("Reservations close at departure or when the driver arrives for boarding.");
    }
    public Trip status(long id,Status input,Actor actor) {
        Trip trip=trips.lock(id,actor.username());String next=input.status();
        if(next.equals("CANCELLED")) {
            if(!actor.dispatch() && !(trip.kind().equals("ON_DEMAND")&&trip.requestedBy().equals(actor.username()))) throw ApiException.forbidden();
            if(!java.util.Set.of("REQUESTED","ASSIGNED","ARRIVED").contains(trip.status())) throw ApiException.conflict("This trip cannot be cancelled in its current state.");
        } else {
            if(!actor.dispatch() && !(actor.role().equals("DRIVER")&&actor.username().equals(trip.driverId()))) throw ApiException.forbidden();
            String expected=Map.of("ASSIGNED","ARRIVED","ARRIVED","IN_PROGRESS","IN_PROGRESS","COMPLETED").get(trip.status());
            if(!next.equals(expected)) throw ApiException.conflict("Invalid trip transition: "+trip.status()+" to "+next+".");
            if(next.equals("IN_PROGRESS")&&trip.kind().equals("SHUTTLE")&&trip.scheduledAt().isAfter(Instant.now())) throw ApiException.conflict("A shuttle cannot depart before its scheduled time.");
        }
        String column=Map.of("ARRIVED","arrived_at","IN_PROGRESS","started_at","COMPLETED","completed_at").get(next);
        if(column==null) db.sql("UPDATE trip SET status=? WHERE id=?").params(next,id).update();
        else db.sql("UPDATE trip SET status=?, "+column+"=? WHERE id=?").params(next,Timestamp.from(Instant.now()),id).update();
        return changed(id,"TRIP_"+next,actor.username());
    }
    public Trip changed(long id,String type,String actor) {
        db.sql("UPDATE trip SET version=version+1 WHERE id=?").param(id).update();
        long version=db.sql("SELECT version FROM trip WHERE id=?").param(id).query(Long.class).single();
        Trip trip=trips.get(id,actor);
        var riders=db.sql("SELECT username FROM reservation WHERE trip_id=? ORDER BY username").param(id).query(String.class).list();
        events.publish("transport.trip-changed",Long.toString(id),version,actor,Map.of("trip",trip,"eventType",type,"authorizedRiders",riders));
        return trip;
    }
    private void validateRoute(long origin,long destination) {
        if(origin==destination) throw ApiException.conflict("Choose different pickup and destination locations.");
        if(db.sql("SELECT COUNT(*) FROM location WHERE id IN (?,?)").params(origin,destination).query(Long.class).single()!=2) throw ApiException.missing("Location");
    }
    private long insert(String kind,long origin,long destination,String purpose,String user,Instant scheduled) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            PreparedStatement ps=connection.prepareStatement("INSERT INTO trip(kind,origin_id,destination_id,purpose,requested_by,status,created_at,scheduled_at) VALUES (?,?,?,?,?,'REQUESTED',?,?)",new String[]{"id"});
            ps.setString(1,kind);ps.setLong(2,origin);ps.setLong(3,destination);ps.setString(4,purpose);ps.setString(5,user);ps.setTimestamp(6,Timestamp.from(Instant.now()));ps.setTimestamp(7,scheduled==null?null:Timestamp.from(scheduled));return ps;
        },key);return key.getKey().longValue();
    }
}
