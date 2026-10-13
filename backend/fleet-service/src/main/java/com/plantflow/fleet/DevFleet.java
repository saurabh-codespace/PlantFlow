package com.plantflow.fleet;

import com.plantflow.shared.security.Actor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DevFleet implements ApplicationRunner {
    private final FleetService fleet;
    private final JdbcClient db;
    public DevFleet(FleetService fleet,JdbcClient db) { this.fleet=fleet; this.db=db; }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        fleet.lockFleet();
        Actor actor=new Actor("system","ADMIN");
        if (db.sql("SELECT COUNT(*) FROM vehicle").query(Long.class).single()==0) {
            fleet.create(new FleetService.NewVehicle("EV-01","BUGGY",6),actor);
            fleet.create(new FleetService.NewVehicle("VAN-02","VAN",12),actor);
            fleet.create(new FleetService.NewVehicle("BUS-03","BUS",40),actor);
        }
        if (db.sql("SELECT COUNT(*) FROM driver").query(Long.class).single()==0) {
            fleet.createDriver(new FleetService.NewDriver("driver","Arjun Mehta"),actor);
            fleet.createDriver(new FleetService.NewDriver("driver2","Priya Singh"),actor);
            fleet.createDriver(new FleetService.NewDriver("driver3","Ravi Kumar"),actor);
        }
        fleet.availability("system");
    }
}
