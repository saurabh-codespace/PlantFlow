package com.plantflow.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.plantflow.shared.events.EventEnvelope;
import com.plantflow.shared.events.Inbox;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsProjection {
    private final JdbcClient db;
    private final Inbox inbox;
    public AnalyticsProjection(JdbcClient db,Inbox inbox) { this.db=db;this.inbox=inbox; }

    @Transactional
    public void apply(EventEnvelope event) {
        if(event.schemaVersion()!=1 || event.aggregateVersion()<1 || event.id()==null || event.occurredAt()==null
            || event.actor()==null || event.payload()==null) throw new IllegalArgumentException("Invalid analytics event envelope");
        if(!Set.of("transport.trip-changed","fleet.availability-changed").contains(event.type()))
            throw new IllegalArgumentException("Unsupported analytics event type");
        // Serialize projection writers across instances; the inbox and facts commit together.
        db.sql("SELECT id FROM projection_state WHERE id=1 FOR UPDATE").query(Integer.class).single();
        if(!inbox.first(event)) return;
        if(event.type().equals("fleet.availability-changed")) {
            if(!"fleet".equals(event.aggregateId())) throw new IllegalArgumentException("Invalid fleet aggregate");
            db.sql("UPDATE projection_state SET fleet_version=?,fleet_busy=?,fleet_available=?,fleet_maintenance=?,updated_at=? WHERE id=1 AND fleet_version<?")
                .params(event.aggregateVersion(),nonNegative(event.payload(),"fleetBusy"),nonNegative(event.payload(),"fleetAvailable"),
                    nonNegative(event.payload(),"fleetMaintenance"),Timestamp.from(Instant.now()),event.aggregateVersion()).update();
            return;
        }
        var trip=event.payload().path("trip");
        long id=nonNegative(trip,"id");
        if(id<1 || !Long.toString(id).equals(event.aggregateId())) throw new IllegalArgumentException("Trip aggregate mismatch");
        long version=db.sql("SELECT aggregate_version FROM trip_fact WHERE trip_id=?").param(id).query(Long.class).optional().orElse(0L);
        if(version>=event.aggregateVersion()) return;
        String kind=required(trip,"kind"),status=required(trip,"status");
        if(!Set.of("ON_DEMAND","SHUTTLE").contains(kind) || !Set.of("REQUESTED","ASSIGNING","ASSIGNED","ARRIVED","IN_PROGRESS","COMPLETED","CANCELLED").contains(status))
            throw new IllegalArgumentException("Invalid trip kind or status");
        Instant created=Instant.parse(required(trip,"createdAt"));
        Double wait=null;
        if(kind.equals("ON_DEMAND") && !status.equals("CANCELLED") && trip.hasNonNull("arrivedAt")) {
            Instant arrived=Instant.parse(required(trip,"arrivedAt"));
            if(arrived.isBefore(created)) throw new IllegalArgumentException("Arrival precedes booking");
            wait=Duration.between(created,arrived).toMillis()/60000.0;
        }
        String route=required(trip,"origin")+" → "+required(trip,"destination");
        long passengers=nonNegative(trip,"passengers");
        Instant projected=Instant.now();
        if(version==0) db.sql("INSERT INTO trip_fact(trip_id,aggregate_version,kind,route,status,passengers,created_at,wait_minutes,updated_at) VALUES (?,?,?,?,?,?,?,?,?)")
            .params(id,event.aggregateVersion(),kind,route,status,passengers,Timestamp.from(created),wait,Timestamp.from(projected)).update();
        else db.sql("UPDATE trip_fact SET aggregate_version=?,kind=?,route=?,status=?,passengers=?,created_at=?,wait_minutes=?,updated_at=? WHERE trip_id=?")
            .params(event.aggregateVersion(),kind,route,status,passengers,Timestamp.from(created),wait,Timestamp.from(projected),id).update();
        db.sql("INSERT INTO activity(id,trip_id,event_type,occurred_at,actor) VALUES (?,?,?,?,?)")
            .params(event.id(),id,required(event.payload(),"eventType"),Timestamp.from(event.occurredAt()),event.actor()).update();
    }

    private static String required(JsonNode node,String field) {
        var value=node.path(field);
        if(!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing field: "+field);
        return value.asText();
    }
    private static long nonNegative(JsonNode node,String field) {
        var value=node.path(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0) throw new IllegalArgumentException("Invalid field: "+field);
        return value.longValue();
    }
}
