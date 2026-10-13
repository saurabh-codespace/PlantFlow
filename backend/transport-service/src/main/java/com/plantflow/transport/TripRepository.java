package com.plantflow.transport;

import com.plantflow.shared.security.Actor;
import com.plantflow.shared.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripRepository {
    private final JdbcClient db;
    public TripRepository(JdbcClient db) { this.db = db; }
    private static final String SELECT = """
        SELECT t.*, o.name AS origin, d.name AS destination,
        COALESCE((SELECT SUM(r.seats) FROM reservation r WHERE r.trip_id=t.id),0) AS passengers,
        (SELECT r.id FROM reservation r WHERE r.trip_id=t.id AND r.username=:viewer) AS my_booking_id
        FROM trip t JOIN location o ON o.id=t.origin_id JOIN location d ON d.id=t.destination_id
        """;
    public List<Trip> list(Actor actor) {
        String filter = actor.dispatch() ? "" : actor.role().equals("DRIVER") ? " WHERE t.driver_id=:viewer" : " WHERE t.requested_by=:viewer OR t.kind='SHUTTLE'";
        // shortcut: bounded operations list; add cursor pagination before a large multi-plant rollout.
        return db.sql(SELECT + filter + " ORDER BY t.created_at DESC, t.id DESC LIMIT 500").param("viewer", actor.username()).query(this::map).list();
    }
    public Trip get(long id, String viewer) {
        return db.sql(SELECT + " WHERE t.id=:id").param("viewer", viewer).param("id", id).query(this::map).optional().orElseThrow(() -> ApiException.missing("Trip"));
    }
    public Trip lock(long id, String viewer) {
        db.sql("SELECT id FROM trip WHERE id=? FOR UPDATE").param(id).query(Long.class).optional().orElseThrow(() -> ApiException.missing("Trip"));
        return get(id, viewer);
    }
    private Instant time(ResultSet rs, String name) throws SQLException { var value=rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private Trip map(ResultSet rs, int row) throws SQLException {
        return new Trip(rs.getLong("id"), rs.getString("kind"), rs.getLong("origin_id"), rs.getLong("destination_id"),
            rs.getString("origin"), rs.getString("destination"), rs.getString("status"), rs.getInt("passengers"),
            rs.getString("requested_by"), rs.getString("purpose"), rs.getObject("vehicle_id", Long.class), rs.getString("vehicle_label"),
            rs.getObject("capacity", Integer.class), rs.getString("driver_id"), rs.getString("driver_name"), time(rs,"scheduled_at"),
            time(rs,"created_at"), time(rs,"assigned_at"), time(rs,"arrived_at"), time(rs,"started_at"), time(rs,"completed_at"), rs.getObject("my_booking_id",Long.class),rs.getString("assignment_error"));
    }
}
