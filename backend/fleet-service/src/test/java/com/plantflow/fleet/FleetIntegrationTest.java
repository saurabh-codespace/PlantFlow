package com.plantflow.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.ApiException;
import com.plantflow.shared.events.EventEnvelope;
import com.plantflow.shared.security.Actor;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:fleet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "plantflow.messaging.enabled=false", "spring.rabbitmq.password=test"
})
class FleetIntegrationTest {
    @Autowired FleetService fleet;
    @Autowired FleetEvents consumer;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    private static final Actor ADMIN=new Actor("dispatcher","DISPATCHER");
    long vehicleId;

    @BeforeEach void setup() {
        for (String table : new String[]{"allocation","trip_assignment","vehicle","driver","inbox_event","outbox_event"})
            db.sql("DELETE FROM "+table).update();
        db.sql("UPDATE fleet_state SET version=0 WHERE id=1").update();
        vehicleId=fleet.create(new FleetService.NewVehicle("EV-01","BUGGY",6),ADMIN).id();
        fleet.createDriver(new FleetService.NewDriver("driver","Driver One"),ADMIN);
        fleet.createDriver(new FleetService.NewDriver("driver2","Driver Two"),ADMIN);
    }
    String request(long tripId,long vehicle,String driver,long version) throws Exception {
        return event("transport.assignment-requested",tripId,version,Map.of("tripId",tripId,"vehicleId",vehicle,"driverId",driver,"passengers",2,"kind","ON_DEMAND"));
    }
    String event(String type,long tripId,long version,Object payload) throws Exception {
        return json.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(),type,1,Long.toString(tripId),version,Instant.now(),"dispatcher",json.valueToTree(payload)));
    }
    long count(String table) { return db.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single(); }

    @Test void duplicateDeliveryHasOneAllocationAndOneConfirmation() throws Exception {
        String request=request(1,vehicleId,"driver",2);
        consumer.receive(request);
        long events=count("outbox_event");
        consumer.receive(request);
        assertThat(count("allocation")).isEqualTo(1);
        assertThat(count("inbox_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(events);
        assertThat(fleet.vehicles().getFirst().busy()).isTrue();
    }

    @Test void concurrentRequestsCannotAllocateSameVehicleOrDriver() throws Exception {
        long secondVehicle=fleet.create(new FleetService.NewVehicle("EV-02","BUGGY",6),ADMIN).id();
        concurrently(request(10,vehicleId,"driver",2),request(11,vehicleId,"driver2",2));
        assertThat(count("allocation")).isEqualTo(1);
        assertThat(db.sql("SELECT COUNT(*) FROM trip_assignment WHERE decision='REJECTED'").query(Long.class).single()).isEqualTo(1);
        String allocatedDriver=db.sql("SELECT driver_id FROM allocation").query(String.class).single();
        consumer.receive(request(12,secondVehicle,allocatedDriver,2));
        assertThat(count("allocation")).isEqualTo(1);
        assertThat(db.sql("SELECT decision FROM trip_assignment WHERE trip_id=12").query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test void concurrentDuplicateDeliveryCommitsOnlyOnce() throws Exception {
        String request=request(20,vehicleId,"driver",2);
        concurrently(request,request);
        assertThat(count("allocation")).isEqualTo(1);
        assertThat(count("inbox_event")).isEqualTo(1);
    }

    private void concurrently(String first,String second) throws Exception {
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> { start.await(); consumer.receive(first); return null; });
            var b=executor.submit(() -> { start.await(); consumer.receive(second); return null; });
            start.countDown();
            a.get(15,TimeUnit.SECONDS);
            b.get(15,TimeUnit.SECONDS);
        }
    }

    @Test void terminalEventReleasesAndDelayedRequestCannotResurrectAllocation() throws Exception {
        consumer.receive(request(30,vehicleId,"driver",2));
        consumer.receive(event("transport.trip-changed",30,6,Map.of("trip",Map.of("id",30,"status","COMPLETED"))));
        assertThat(count("allocation")).isZero();
        consumer.receive(request(30,vehicleId,"driver",2));
        assertThat(count("allocation")).isZero();
        consumer.receive(event("transport.trip-changed",31,4,Map.of("trip",Map.of("id",31,"status","CANCELLED"))));
        consumer.receive(request(31,vehicleId,"driver",2));
        assertThat(count("allocation")).isZero();
        assertThat(fleet.vehicles().getFirst().busy()).isFalse();
    }

    @Test void unavailableResourcesRejectAndLaterVersionCanRetry() throws Exception {
        fleet.status(vehicleId,new FleetService.VehicleStatus("MAINTENANCE"),ADMIN);
        consumer.receive(request(40,vehicleId,"driver",2));
        assertThat(count("allocation")).isZero();
        fleet.status(vehicleId,new FleetService.VehicleStatus("AVAILABLE"),ADMIN);
        consumer.receive(request(40,vehicleId,"driver",2));
        assertThat(count("allocation")).isZero();
        consumer.receive(request(40,vehicleId,"driver",4));
        assertThat(count("allocation")).isEqualTo(1);
        assertThatThrownBy(() -> fleet.status(vehicleId,new FleetService.VehicleStatus("MAINTENANCE"),ADMIN)).isInstanceOf(ApiException.class);
        assertThat(fleet.vehicles().getFirst().status()).isEqualTo("AVAILABLE");
    }

    @Test void malformedEventRollsBackInboxAndDomainMutation() throws Exception {
        String malformed=event("transport.assignment-requested",50,2,Map.of("tripId",51,"vehicleId",vehicleId,"driverId","driver","passengers",2,"kind","ON_DEMAND"));
        assertThatThrownBy(() -> consumer.receive(malformed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("allocation")).isZero();
        assertThat(count("inbox_event")).isZero();
        String invalidPayload=event("transport.assignment-requested",50,2,Map.of("tripId",50,"driverId",java.util.List.of("invalid")));
        assertThatThrownBy(() -> consumer.receive(invalidPayload)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThat(count("inbox_event")).isZero();
    }

    @Test void employeeCannotManageFleetOrReadDriverDirectory() {
        Actor employee=new Actor("employee","EMPLOYEE");
        assertThatThrownBy(() -> fleet.drivers(employee)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> fleet.create(new FleetService.NewVehicle("EV-99","BUGGY",4),employee)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> fleet.createDriver(new FleetService.NewDriver("unauthorized","Unauthorized"),employee)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> fleet.status(vehicleId,new FleetService.VehicleStatus("MAINTENANCE"),employee)).isInstanceOf(ApiException.class);
        assertThat(count("vehicle")).isEqualTo(1);
        assertThat(count("driver")).isEqualTo(2);
    }
}
