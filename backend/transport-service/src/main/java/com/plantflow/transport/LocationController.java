package com.plantflow.transport;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocationController {
    private final JdbcClient db;
    public LocationController(JdbcClient db) { this.db=db; }
    public record Location(long id,String name,String zone,int x,int y) {}
    @GetMapping("/api/locations") public List<Location> locations() { return db.sql("SELECT * FROM location ORDER BY id").query(Location.class).list(); }
}
