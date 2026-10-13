package com.plantflow.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.ApiException;
import com.plantflow.shared.events.EventEnvelope;
import com.plantflow.shared.security.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:tracking_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","plantflow.messaging.enabled=false","logging.level.org.springframework=INFO"})
@AutoConfigureMockMvc
class TrackingServiceTest {
    @Autowired TrackingService service;
    @Autowired TripAccessConsumer consumer;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    final Actor driver=new Actor("driver","DRIVER"),rider=new Actor("rider","EMPLOYEE"),otherRider=new Actor("other-rider","EMPLOYEE"),outsider=new Actor("outsider","EMPLOYEE"),dispatcher=new Actor("dispatch","DISPATCHER");

    @BeforeEach void clean() {
        for(String table:List.of("latest_position","trip_rider","tracked_trip","inbox_event","outbox_event")) db.sql("DELETE FROM "+table).update();
    }

    @Test void projectedAccessDeniesOutsidersAndLimitsCoRiderVisibility() throws Exception {
        assertThatThrownBy(()->service.update(1,fix(),rider)).isInstanceOf(ApiException.class);
        consumer.accept(snapshot(1,"ASSIGNED","rider","other-rider"));
        service.update(1,fix(),driver);service.update(1,fix(),rider);service.update(1,fix(),otherRider);
        assertThatThrownBy(()->service.update(1,fix(),outsider)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.update(1,fix(),new Actor("other-driver","DRIVER"))).isInstanceOf(ApiException.class);
        assertThat(service.positions(outsider)).isEmpty();
        assertThat(service.positions(rider)).extracting(TrackingService.Position::username).containsExactlyInAnyOrder("driver","rider");
        assertThat(service.positions(otherRider)).extracting(TrackingService.Position::username).containsExactlyInAnyOrder("driver","other-rider");
        assertThat(service.positions(driver)).hasSize(3);
        assertThat(service.positions(dispatcher)).hasSize(3);
    }

    @Test void reservationRevocationPurgesRiderAndOlderSnapshotCannotRestoreAccess() throws Exception {
        consumer.accept(snapshot(2,"ASSIGNED","rider","other-rider"));
        service.update(1,fix(),rider);service.update(1,fix(),otherRider);
        consumer.accept(snapshot(3,"ASSIGNED","other-rider"));
        consumer.accept(snapshot(1,"ASSIGNED","rider","other-rider"));
        assertThat(service.positions(rider)).isEmpty();
        assertThat(service.positions(dispatcher)).extracting(TrackingService.Position::username).containsExactly("other-rider");
        assertThatThrownBy(()->service.update(1,fix(),rider)).isInstanceOf(ApiException.class);
    }

    @Test void terminalEventPurgesPositionsAndStaleEventCannotReopenSharing() throws Exception {
        var assigned=snapshot(1,"ASSIGNED","rider");consumer.accept(assigned);consumer.accept(assigned);
        assertThat(db.sql("SELECT COUNT(*) FROM inbox_event").query(Long.class).single()).isEqualTo(1);
        service.update(1,fix(),rider);
        consumer.accept(snapshot(3,"COMPLETED","rider"));
        consumer.accept(snapshot(2,"IN_PROGRESS","rider"));
        assertThat(service.positions(dispatcher)).isEmpty();
        assertThat(db.sql("SELECT COUNT(*) FROM latest_position").query(Long.class).single()).isZero();
        assertThatThrownBy(()->service.update(1,fix(),rider)).isInstanceOf(ApiException.class);
    }

    @Test void fixesMustBeFreshMonotonicFiniteAndRateLimited() throws Exception {
        consumer.accept(snapshot(1,"ASSIGNED","rider"));
        for(var bad:List.of(new TrackingService.Update(20.0,70.0,5.0,Instant.now().minusSeconds(70)),new TrackingService.Update(20.0,70.0,5.0,Instant.now().plusSeconds(10)),new TrackingService.Update(Double.NaN,70.0,5.0,Instant.now())))
            assertThatThrownBy(()->service.update(1,bad,rider)).isInstanceOf(ApiException.class);
        var first=fix();service.update(1,first,rider);
        assertThatThrownBy(()->service.update(1,first,rider)).isInstanceOf(ApiException.class).hasMessageContaining("older");
        assertThatThrownBy(()->service.update(1,new TrackingService.Update(20.1,70.1,5.0,first.recordedAt().plusMillis(1)),rider)).isInstanceOf(ApiException.class).hasMessageContaining("one GPS");
        db.sql("UPDATE latest_position SET received_at=?").param(Timestamp.from(Instant.now().minusSeconds(2))).update();
        var updated=service.update(1,new TrackingService.Update(20.1,70.1,5.0,first.recordedAt().plusMillis(2)),rider);
        assertThat(updated.latitude()).isEqualTo(20.1);
        assertThat(db.sql("SELECT COUNT(*) FROM latest_position").query(Long.class).single()).isEqualTo(1);
    }

    @Test void stopSharingRemovesOnlyOwnFixAndOldFixesExpire() throws Exception {
        consumer.accept(snapshot(1,"ASSIGNED","rider","other-rider"));
        service.update(1,fix(),rider);service.update(1,fix(),otherRider);
        service.stop(1,outsider);
        assertThat(service.positions(dispatcher)).hasSize(2);
        service.stop(1,rider);
        assertThat(service.positions(dispatcher)).extracting(TrackingService.Position::username).containsExactly("other-rider");
        db.sql("UPDATE latest_position SET received_at=?,recorded_at=?").params(Timestamp.from(Instant.now().minusSeconds(1000)),Timestamp.from(Instant.now().minusSeconds(1000))).update();
        assertThat(service.positions(dispatcher)).isEmpty();
        service.expire();
        assertThat(db.sql("SELECT COUNT(*) FROM latest_position").query(Long.class).single()).isZero();
    }

    @Test void deviceClaimBindsTelemetryToOneVehicleAndCannotUseHumanApis() throws Exception {
        consumer.accept(snapshot(1,"ASSIGNED","rider"));
        String payload=json.writeValueAsString(fix());
        mvc.perform(post("/api/tracking/device/positions").with(jwt().jwt(j->j.claim("vehicle_id",7)).authorities(new SimpleGrantedAuthority("ROLE_DEVICE")))
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andExpect(jsonPath("vehicleId").value(7)).andExpect(jsonPath("subject").value("VEHICLE"));
        mvc.perform(post("/api/tracking/device/positions").with(jwt().jwt(j->j.claim("vehicle_id",8)).authorities(new SimpleGrantedAuthority("ROLE_DEVICE")))
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        mvc.perform(post("/api/tracking/device/positions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DEVICE")))
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(get("/api/tracking/positions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DEVICE")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tracking/device/positions").with(jwt().jwt(j->j.claim("vehicle_id",7)).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
    }

    @Test void httpValidatesCoordinatesAndEnforcesTripOwnership() throws Exception {
        consumer.accept(snapshot(1,"ASSIGNED","rider"));
        mvc.perform(get("/api/tracking/positions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tracking/trips/1/positions").with(jwt().jwt(j->j.subject("outsider")).authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fix()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tracking/trips/1/positions").with(jwt().jwt(j->j.subject("rider")).authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new TrackingService.Update(91.0,70.0,5.0,Instant.now())))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tracking/trips/1/positions").with(jwt().jwt(j->j.subject("rider")).authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fix()))).andExpect(status().isOk());
    }

    @Test void malformedEnvelopeCannotPoisonAccessAndInboxRollsBack() throws Exception {
        var valid=json.readValue(snapshot(1,"ASSIGNED","rider"),EventEnvelope.class);
        var wrong=new EventEnvelope(valid.id(),valid.type(),1,"999",1,valid.occurredAt(),valid.actor(),valid.payload());
        assertThatThrownBy(()->consumer.accept(json.writeValueAsString(wrong))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.sql("SELECT COUNT(*) FROM tracked_trip").query(Long.class).single()).isZero();
        consumer.accept(json.writeValueAsString(valid));
        service.update(1,fix(),rider);
        assertThat(service.positions(rider)).hasSize(1);
    }

    private TrackingService.Update fix() { return new TrackingService.Update(20.0,70.0,5.0,Instant.now()); }
    private String snapshot(long version,String status,String... riders) throws Exception {
        var trip=json.createObjectNode().put("id",1).put("status",status).put("driverId","driver").put("vehicleId",7).put("vehicleLabel","BUS-7");
        var payload=json.createObjectNode();payload.set("trip",trip);payload.set("authorizedRiders",json.valueToTree(riders));
        return json.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(),"transport.trip-changed",1,"1",version,Instant.now(),"dispatch",payload));
    }
}
