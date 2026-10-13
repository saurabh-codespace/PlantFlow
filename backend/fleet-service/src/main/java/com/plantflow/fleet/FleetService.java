package com.plantflow.fleet;

import com.plantflow.shared.ApiException;
import com.plantflow.shared.events.Outbox;
import com.plantflow.shared.security.Actor;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetService {
    private final JdbcClient db;
    private final Outbox outbox;
    public FleetService(JdbcClient db, Outbox outbox) { this.db = db; this.outbox = outbox; }
    public record Vehicle(long id, String code, String type, int capacity, String status, boolean busy) {}
    public record Driver(String username, String name, boolean busy) {}
    public record NewVehicle(@NotBlank @Pattern(regexp="[A-Za-z0-9 -]{2,30}") String code,
        @NotNull @Pattern(regexp="BUGGY|VAN|BUS") String type, @Min(1) @Max(60) int capacity) {}
    public record NewDriver(@NotBlank @Pattern(regexp="[A-Za-z0-9_.@-]{1,100}") String username,
        @NotBlank @Size(max=100) String name) {}
    public record VehicleStatus(@NotNull @Pattern(regexp="AVAILABLE|MAINTENANCE") String status) {}

    public List<Vehicle> vehicles() {
        return db.sql("SELECT v.*, EXISTS(SELECT 1 FROM allocation a WHERE a.vehicle_id=v.id) AS busy FROM vehicle v ORDER BY v.id").query(Vehicle.class).list();
    }
    public List<Driver> drivers(Actor actor) {
        actor.requireDispatch();
        return db.sql("SELECT d.*, EXISTS(SELECT 1 FROM allocation a WHERE a.driver_id=d.username) AS busy FROM driver d ORDER BY d.name").query(Driver.class).list();
    }
    // shortcut: fleet writes serialize per plant; partition this counter when dispatch volume needs concurrent writes.
    void lockFleet() { db.sql("SELECT version FROM fleet_state WHERE id=1 FOR UPDATE").query(Long.class).single(); }

    void availability(String actor) {
        db.sql("UPDATE fleet_state SET version=version+1 WHERE id=1").update();
        long version = db.sql("SELECT version FROM fleet_state WHERE id=1").query(Long.class).single();
        var vehicles = vehicles();
        outbox.publish("fleet.availability-changed", "fleet", version, actor, Map.of(
            "fleetBusy", vehicles.stream().filter(Vehicle::busy).count(),
            "fleetAvailable", vehicles.stream().filter(v -> !v.busy() && v.status().equals("AVAILABLE")).count(),
            "fleetMaintenance", vehicles.stream().filter(v -> v.status().equals("MAINTENANCE")).count()));
    }
    @Transactional
    public Vehicle create(NewVehicle request, Actor actor) {
        actor.requireDispatch();
        lockFleet();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (code.length() < 2) throw ApiException.conflict("Vehicle code must contain at least two characters.");
        if (db.sql("SELECT COUNT(*) FROM vehicle WHERE code=?").param(code).query(Long.class).single()>0)
            throw ApiException.conflict("A vehicle with this code already exists.");
        db.sql("INSERT INTO vehicle(code,type,capacity) VALUES (?,?,?)").params(code,request.type(),request.capacity()).update();
        availability(actor.username());
        return vehicles().stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow();
    }
    @Transactional
    public Driver createDriver(NewDriver request, Actor actor) {
        actor.requireDispatch();
        lockFleet();
        if (db.sql("SELECT COUNT(*) FROM driver WHERE username=?").param(request.username()).query(Long.class).single()>0)
            throw ApiException.conflict("This driver is already registered.");
        db.sql("INSERT INTO driver(username,name) VALUES (?,?)").params(request.username(), request.name().trim()).update();
        return new Driver(request.username(),request.name().trim(),false);
    }
    @Transactional
    public Vehicle status(long id, VehicleStatus request, Actor actor) {
        actor.requireDispatch();
        lockFleet();
        db.sql("SELECT id FROM vehicle WHERE id=? FOR UPDATE").param(id).query(Long.class).optional().orElseThrow(() -> ApiException.missing("Vehicle"));
        if (db.sql("SELECT COUNT(*) FROM allocation WHERE vehicle_id=?").param(id).query(Long.class).single()>0)
            throw ApiException.conflict("Finish or cancel the vehicle's active trip first.");
        db.sql("UPDATE vehicle SET status=? WHERE id=?").params(request.status(),id).update();
        availability(actor.username());
        return vehicles().stream().filter(v -> v.id()==id).findFirst().orElseThrow();
    }
}
