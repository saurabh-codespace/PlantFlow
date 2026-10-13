package com.plantflow.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plantflow.shared.ApiException;
import com.plantflow.shared.events.EventEnvelope;
import com.plantflow.shared.security.Actor;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:analytics_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "plantflow.messaging.enabled=false"
})
@AutoConfigureMockMvc
class AnalyticsServiceTest {
    @Autowired AnalyticsProjection projection;
    @Autowired AnalyticsService service;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    private final Actor dispatcher=new Actor("dispatcher","DISPATCHER");
    private Instant today;

    @BeforeEach void clean() {
        db.sql("DELETE FROM activity").update();
        db.sql("DELETE FROM trip_fact").update();
        db.sql("DELETE FROM inbox_event").update();
        db.sql("UPDATE projection_state SET fleet_version=0,fleet_busy=0,fleet_available=0,fleet_maintenance=0,updated_at=NULL").update();
        today=LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Test void duplicateAndOlderSnapshotsNeverRegressFactsOrDuplicateActivity() {
        var completed=trip(1,3,"ON_DEMAND","COMPLETED",2,today,4,"Gate");
        projection.apply(completed);
        projection.apply(completed);
        projection.apply(trip(1,2,"ON_DEMAND","ARRIVED",2,today,4,"Gate"));
        var report=service.report(1,dispatcher);
        assertThat(report.totalTrips()).isEqualTo(1);
        assertThat(report.completedTrips()).isEqualTo(1);
        assertThat(report.passengersMoved()).isEqualTo(2);
        assertThat(service.events(dispatcher)).hasSize(1);
    }

    @Test void fleetSnapshotCountsAreVersionedAndIndependent() {
        projection.apply(fleet(4,2,5,1));
        projection.apply(fleet(3,9,0,0));
        var report=service.report(7,dispatcher);
        assertThat(report.fleetBusy()).isEqualTo(2);
        assertThat(report.fleetAvailable()).isEqualTo(5);
        assertThat(report.fleetMaintenance()).isEqualTo(1);
        assertThat(report.lastUpdated()).isNotNull();
    }

    @Test void kpisUseUtcCreationWindowAndOnlyCompletedPassengersAndNonCancelledOnDemandWait() {
        projection.apply(trip(1,1,"ON_DEMAND","COMPLETED",2,today,4,"Gate"));
        projection.apply(trip(2,1,"SHUTTLE","COMPLETED",12,today,100,"Gate"));
        projection.apply(trip(3,1,"ON_DEMAND","CANCELLED",3,today,50,"Gate"));
        projection.apply(trip(4,1,"ON_DEMAND","ARRIVED",4,today,8,"Gate"));
        projection.apply(trip(5,1,"ON_DEMAND","COMPLETED",40,today.minusSeconds(1),1,"Old"));
        projection.apply(trip(6,1,"ON_DEMAND","COMPLETED",40,today.plus(Duration.ofDays(1)),1,"Future"));
        var report=service.report(1,dispatcher);
        assertThat(report.totalTrips()).isEqualTo(4);
        assertThat(report.completedTrips()).isEqualTo(2);
        assertThat(report.cancelledTrips()).isEqualTo(1);
        assertThat(report.activeTrips()).isEqualTo(1);
        assertThat(report.passengersMoved()).isEqualTo(14);
        assertThat(report.completionRate()).isEqualTo(50);
        assertThat(report.averageWaitMinutes()).isEqualTo(6);
        assertThat(report.daily()).containsExactly(new AnalyticsService.Daily(LocalDate.now(ZoneOffset.UTC).toString(),4,2,14));
        assertThat(report.routes()).containsExactly(new AnalyticsService.Route("Gate → Unit A",4,14));
        assertThat(report.byKind()).containsExactly(new AnalyticsService.Kind("ON_DEMAND",3),new AnalyticsService.Kind("SHUTTLE",1));
        assertThat(service.csv(1,dispatcher)).doesNotContain("Old","Future");
    }

    @Test void failedProjectionRollsBackInboxSoCorrectedRetryCanSucceed() {
        var valid=trip(1,1,"ON_DEMAND","COMPLETED",2,today,4,"Gate");
        var invalidPayload=valid.payload().deepCopy();
        ((ObjectNode)invalidPayload.path("trip")).put("status","INVALID");
        var invalid=new EventEnvelope(valid.id(),valid.type(),1,valid.aggregateId(),1,valid.occurredAt(),valid.actor(),invalidPayload);
        assertThatThrownBy(()->projection.apply(invalid)).isInstanceOf(IllegalArgumentException.class);
        projection.apply(valid);
        assertThat(service.report(1,dispatcher).completedTrips()).isEqualTo(1);
    }

    @Test void csvQuotesCommasQuotesAndNeutralizesFormulaPrefixes() {
        assertThat(AnalyticsService.cell("=SUM(1,2)")).isEqualTo("\"'=SUM(1,2)\"");
        assertThat(AnalyticsService.cell("  @SUM(1)")).startsWith("\"'");
        assertThat(AnalyticsService.cell("\t1+1")).startsWith("\"'");
        assertThat(AnalyticsService.cell("Gate, \"A\"")).isEqualTo("\"Gate, \"\"A\"\"\"");
        projection.apply(trip(1,1,"ON_DEMAND","COMPLETED",2,today,4,"=CMD()"));
        assertThat(service.csv(1,dispatcher)).contains("\"'=CMD() → Unit A\"");
    }

    @Test void eventsAreLimitedAndOrderedNewestFirst() {
        for(int i=1;i<=55;i++) {
            var event=trip(i,1,"ON_DEMAND","REQUESTED",1,today,-1,"Gate");
            projection.apply(new EventEnvelope(event.id(),event.type(),1,event.aggregateId(),1,today.plusSeconds(i),event.actor(),event.payload()));
        }
        var events=service.events(dispatcher);
        assertThat(events).hasSize(50);
        assertThat(events.getFirst().tripId()).isEqualTo(55);
        assertThat(events.getLast().tripId()).isEqualTo(6);
    }

    @Test void reportWindowAndServiceAuthorizationAreEnforced() {
        assertThatThrownBy(()->service.report(0,dispatcher)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.report(91,dispatcher)).isInstanceOf(ApiException.class);
        var employee=new Actor("employee","EMPLOYEE");
        assertThatThrownBy(()->service.report(7,employee)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.csv(7,employee)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.events(employee)).isInstanceOf(ApiException.class);
    }

    @Test void authenticatedHttpEndpointsRequireDispatcherRole() throws Exception {
        for(String path:new String[]{"/api/analytics","/api/analytics/export","/api/events"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(jwt().jwt(j->j.claim("preferred_username","employee")).authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().jwt(j->j.claim("preferred_username","dispatcher")).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk());
        }
    }

    private EventEnvelope trip(long id,long version,String kind,String status,int passengers,Instant created,int wait,String origin) {
        ObjectNode trip=json.createObjectNode().put("id",id).put("kind",kind).put("status",status).put("passengers",passengers)
            .put("createdAt",created.toString()).put("origin",origin).put("destination","Unit A");
        if(wait>=0) trip.put("arrivedAt",created.plusSeconds(wait*60L).toString());
        var payload=json.createObjectNode().put("eventType","TRIP_"+status).set("trip",trip);
        return new EventEnvelope(UUID.randomUUID().toString(),"transport.trip-changed",1,Long.toString(id),version,Instant.now(),"dispatcher",payload);
    }
    private EventEnvelope fleet(long version,int busy,int available,int maintenance) {
        var payload=json.createObjectNode().put("fleetBusy",busy).put("fleetAvailable",available).put("fleetMaintenance",maintenance);
        return new EventEnvelope(UUID.randomUUID().toString(),"fleet.availability-changed",1,"fleet",version,Instant.now(),"dispatcher",payload);
    }
}
