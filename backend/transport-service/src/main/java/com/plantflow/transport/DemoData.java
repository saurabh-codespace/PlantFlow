package com.plantflow.transport;

import com.plantflow.shared.security.Actor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DemoData implements ApplicationRunner {
    private final JdbcClient db;private final TripService trips;
    public DemoData(JdbcClient db,TripService trips) { this.db=db;this.trips=trips; }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(db.sql("SELECT COUNT(*) FROM location").query(Long.class).single()>0) return;
        String[][] places={{"Main gate","Access","15","72"},{"Administration","Office","33","37"},{"Turbine hall","Generation","67","40"},{"Control room","Operations","72","66"},{"Workshop","Maintenance","44","78"},{"Staff colony","Residential","15","18"}};
        for(var p:places) db.sql("INSERT INTO location(name,zone,x,y) VALUES (?,?,?,?)").params(p[0],p[1],Integer.parseInt(p[2]),Integer.parseInt(p[3])).update();
        var ids=db.sql("SELECT id FROM location ORDER BY id").query(Long.class).list();
        trips.request(new TripService.Request(ids.get(0),ids.get(2),2,"Maintenance team transfer"),new Actor("employee","EMPLOYEE"));
        trips.request(new TripService.Request(ids.get(1),ids.get(3),1,"Control room inspection"),new Actor("dispatcher","DISPATCHER"));
    }
}
