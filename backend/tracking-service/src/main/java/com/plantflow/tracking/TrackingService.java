package com.plantflow.tracking;

import com.plantflow.shared.ApiException;
import com.plantflow.shared.security.Actor;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackingService {
    private final JdbcClient db;
    public TrackingService(JdbcClient db) { this.db=db; }
    public record Update(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull @DecimalMin("0") @DecimalMax("10000") Double accuracy,@NotNull Instant recordedAt) {}
    public record Position(long tripId,String subject,String username,Double latitude,Double longitude,Double accuracy,Instant recordedAt,Long vehicleId,String vehicleLabel) {}
    record Access(long tripId,String status,String driverId,Long vehicleId) {}
    private void lock() { db.sql("SELECT id FROM tracking_guard WHERE id=1 FOR UPDATE").query(Integer.class).single(); }
    private boolean active(String status) { return java.util.Set.of("ASSIGNED","ARRIVED","IN_PROGRESS").contains(status); }
    private boolean rider(long id,String user) { return db.sql("SELECT COUNT(*) FROM trip_rider WHERE trip_id=? AND username=?").params(id,user).query(Long.class).single()>0; }
    private Access access(long id) { return db.sql("SELECT trip_id,status,driver_id,vehicle_id FROM tracked_trip WHERE trip_id=?").param(id).query(Access.class).optional().orElseThrow(()->ApiException.conflict("Trip access is still synchronizing. Try again shortly.")); }
    @Transactional
    public Position update(long id,Update input,Actor actor) {
        lock();var trip=access(id);
        if(!active(trip.status())) throw ApiException.conflict("Live sharing is only available during an assigned trip.");
        String subject;
        if(actor.role().equals("DRIVER")&&actor.username().equals(trip.driverId())) subject="DRIVER";
        else if(!actor.role().equals("DRIVER")&&rider(id,actor.username())) subject="RIDER";
        else throw ApiException.forbidden();
        return write(id,subject,actor.username(),input);
    }
    @Transactional
    public Position device(long vehicleId,Update input) {
        lock();
        var trips=db.sql("SELECT trip_id FROM tracked_trip WHERE vehicle_id=? AND status IN ('ASSIGNED','ARRIVED','IN_PROGRESS')").param(vehicleId).query(Long.class).list();
        if(trips.size()!=1) throw ApiException.conflict("Vehicle needs one active trip before telemetry can be accepted.");
        return write(trips.getFirst(),"VEHICLE","vehicle-"+vehicleId,input);
    }
    private Position write(long id,String subject,String user,Update input) {
        Instant now=Instant.now();
        // PostgreSQL/H2 timestamps preserve microseconds; normalize before replay comparisons.
        Instant recorded=input.recordedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(!Double.isFinite(input.latitude())||!Double.isFinite(input.longitude())||!Double.isFinite(input.accuracy())||recorded.isBefore(now.minusSeconds(60))||recorded.isAfter(now.plusSeconds(5)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"Use a valid GPS fix from the last 60 seconds.");
        var previous=db.sql("SELECT recorded_at,received_at FROM latest_position WHERE trip_id=? AND subject=? AND username=?").params(id,subject,user)
            .query((rs,n)->new Instant[]{rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant()}).optional();
        if(previous.isPresent()) {
            if(!recorded.isAfter(previous.get()[0])) throw ApiException.conflict("This position is older than the last accepted fix.");
            if(previous.get()[1].isAfter(now.minusSeconds(1))) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Send at most one GPS update per second.");
            db.sql("DELETE FROM latest_position WHERE trip_id=? AND subject=? AND username=?").params(id,subject,user).update();
        }
        db.sql("INSERT INTO latest_position(trip_id,subject,username,latitude,longitude,accuracy,recorded_at,received_at) VALUES (?,?,?,?,?,?,?,?)")
            .params(id,subject,user,input.latitude(),input.longitude(),input.accuracy(),Timestamp.from(recorded),Timestamp.from(now)).update();
        return db.sql(SELECT+" WHERE p.trip_id=? AND p.subject=? AND p.username=?").params(id,subject,user).query(this::map).single();
    }
    @Transactional
    public void stop(long id,Actor actor) { lock();db.sql("DELETE FROM latest_position WHERE trip_id=? AND username=?").params(id,actor.username()).update(); }
    private static final String SELECT="SELECT p.*,t.vehicle_id,t.vehicle_label FROM latest_position p JOIN tracked_trip t ON t.trip_id=p.trip_id";
    public List<Position> positions(Actor actor) {
        String filter=actor.dispatch()?"":" AND (t.driver_id=:user OR EXISTS (SELECT 1 FROM trip_rider r WHERE r.trip_id=t.trip_id AND r.username=:user)) AND (p.subject<>'RIDER' OR p.username=:user OR t.driver_id=:user)";
        var sql=db.sql(SELECT+" WHERE p.recorded_at>:since AND t.status IN ('ASSIGNED','ARRIVED','IN_PROGRESS')"+filter+" ORDER BY p.trip_id,p.subject,p.username").param("since",Timestamp.from(Instant.now().minusSeconds(300)));
        if(!actor.dispatch()) sql.param("user",actor.username());
        return sql.query(this::map).list();
    }
    private Position map(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Position(rs.getLong("trip_id"),rs.getString("subject"),rs.getString("username"),rs.getDouble("latitude"),rs.getDouble("longitude"),rs.getDouble("accuracy"),rs.getTimestamp("recorded_at").toInstant(),rs.getObject("vehicle_id",Long.class),rs.getString("vehicle_label"));
    }
    @Scheduled(fixedDelay=60000)
    public void expire() { db.sql("DELETE FROM latest_position WHERE received_at<?").param(Timestamp.from(Instant.now().minusSeconds(900))).update(); }
}
