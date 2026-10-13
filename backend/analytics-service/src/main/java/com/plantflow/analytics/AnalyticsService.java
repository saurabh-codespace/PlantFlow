package com.plantflow.analytics;

import com.plantflow.shared.security.Actor;
import com.plantflow.shared.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {
    private final JdbcClient db;
    public AnalyticsService(JdbcClient db) { this.db=db; }
    public record Fact(long tripId,String kind,String route,String status,int passengers,Instant createdAt,Double waitMinutes,Instant updatedAt) {}
    public record Daily(String date,long trips,long completed,long passengers) {}
    public record Route(String route,long trips,long passengers) {}
    public record Kind(String kind,long trips) {}
    public record Activity(String id,long tripId,String type,Instant occurredAt,String actor) {}
    public record Report(long totalTrips,long completedTrips,long cancelledTrips,long activeTrips,double completionRate,
        Double averageWaitMinutes,long passengersMoved,long fleetBusy,long fleetAvailable,long fleetMaintenance,
        List<Daily> daily,List<Route> routes,List<Kind> byKind,Instant lastUpdated) {}

    private List<Fact> facts(int days,Actor actor,LocalDate today) {
        actor.requireDispatch();
        if(days<1 || days>90) throw new ApiException(HttpStatus.BAD_REQUEST,"Report window must be between 1 and 90 days.");
        Instant start=today.minusDays(days-1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end=today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return db.sql("SELECT * FROM trip_fact WHERE created_at>=? AND created_at<? ORDER BY created_at,trip_id")
            .params(Timestamp.from(start),Timestamp.from(end)).query((rs,n) -> new Fact(rs.getLong("trip_id"),rs.getString("kind"),rs.getString("route"),rs.getString("status"),rs.getInt("passengers"),rs.getTimestamp("created_at").toInstant(),rs.getObject("wait_minutes",Double.class),rs.getTimestamp("updated_at").toInstant())).list();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report report(int days,Actor actor) {
        var today=LocalDate.now(ZoneOffset.UTC);
        var facts=facts(days,actor,today);
        long completed=facts.stream().filter(f->f.status().equals("COMPLETED")).count();
        long cancelled=facts.stream().filter(f->f.status().equals("CANCELLED")).count();
        var wait=facts.stream().filter(f->f.kind().equals("ON_DEMAND") && f.waitMinutes()!=null && !f.status().equals("CANCELLED")).mapToDouble(Fact::waitMinutes).average();
        var daily=new ArrayList<Daily>();
        for(int i=days-1;i>=0;i--) {
            var day=today.minusDays(i);
            var group=facts.stream().filter(f->f.createdAt().atZone(ZoneOffset.UTC).toLocalDate().equals(day)).toList();
            daily.add(new Daily(day.toString(),group.size(),group.stream().filter(f->f.status().equals("COMPLETED")).count(),moved(group)));
        }
        var grouped=new TreeMap<String,List<Fact>>();facts.forEach(f->grouped.computeIfAbsent(f.route(),k->new ArrayList<>()).add(f));
        var routes=grouped.entrySet().stream().map(e->new Route(e.getKey(),e.getValue().size(),moved(e.getValue())))
            .sorted(Comparator.comparingLong(Route::trips).reversed().thenComparing(Route::route)).toList();
        var kinds=List.of(new Kind("ON_DEMAND",facts.stream().filter(f->f.kind().equals("ON_DEMAND")).count()),new Kind("SHUTTLE",facts.stream().filter(f->f.kind().equals("SHUTTLE")).count()));
        var fleet=db.sql("SELECT * FROM projection_state WHERE id=1").query((rs,n)->new Fleet(rs.getLong("fleet_busy"),rs.getLong("fleet_available"),rs.getLong("fleet_maintenance"),rs.getTimestamp("updated_at")==null?null:rs.getTimestamp("updated_at").toInstant())).single();
        Instant latest=facts.stream().map(Fact::updatedAt).max(Comparator.naturalOrder()).orElse(null);
        if(fleet.updatedAt()!=null && (latest==null || fleet.updatedAt().isAfter(latest))) latest=fleet.updatedAt();
        return new Report(facts.size(),completed,cancelled,facts.size()-completed-cancelled,facts.isEmpty()?0:100.0*completed/facts.size(),
            wait.isPresent()?wait.getAsDouble():null,moved(facts),fleet.busy(),fleet.available(),fleet.maintenance(),daily,routes,kinds,latest);
    }
    private record Fleet(long busy,long available,long maintenance,Instant updatedAt) {}
    private long moved(List<Fact> facts) { return facts.stream().filter(f->f.status().equals("COMPLETED")).mapToLong(Fact::passengers).sum(); }
    public List<Activity> events(Actor actor) {
        actor.requireDispatch();
        return db.sql("SELECT * FROM activity ORDER BY occurred_at DESC,id DESC LIMIT 50")
            .query((rs,n)->new Activity(rs.getString("id"),rs.getLong("trip_id"),rs.getString("event_type"),rs.getTimestamp("occurred_at").toInstant(),rs.getString("actor"))).list();
    }
    public String csv(int days,Actor actor) {
        StringBuilder csv=new StringBuilder("trip_id,kind,route,status,booked_passengers,created_at_utc,wait_minutes\r\n");
        for(var f:facts(days,actor,LocalDate.now(ZoneOffset.UTC))) csv.append(f.tripId()).append(',').append(cell(f.kind())).append(',').append(cell(f.route())).append(',')
            .append(cell(f.status())).append(',').append(f.passengers()).append(',').append(f.createdAt()).append(',').append(f.waitMinutes()==null?"":f.waitMinutes()).append("\r\n");
        return csv.toString();
    }
    static String cell(String value) {
        String stripped=value.stripLeading();
        if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0))>=0) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
