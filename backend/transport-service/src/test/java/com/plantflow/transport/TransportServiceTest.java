package com.plantflow.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.ApiException;
import com.plantflow.shared.events.EventEnvelope;
import com.plantflow.shared.security.Actor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:transport_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","plantflow.messaging.enabled=false","logging.level.org.springframework=INFO"})
@AutoConfigureMockMvc
class TransportServiceTest {
    @Autowired TripService service;
    @Autowired TripRepository trips;
    @Autowired AssignmentConsumer consumer;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    final Actor dispatch=new Actor("dispatch","DISPATCHER"),employee=new Actor("employee","EMPLOYEE"),driver=new Actor("driver","DRIVER");

    @BeforeEach void clean() {
        for(String table:List.of("reservation","trip","location","outbox_event","inbox_event")) db.sql("DELETE FROM "+table).update();
        db.sql("INSERT INTO location(id,name,zone,x,y) VALUES (1,'Gate','Access',10,20),(2,'Turbine','Generation',40,50)").update();
    }

    @Test void assignmentPersistsRequestAndAppliesConfirmationOnce() throws Exception {
        var trip=request();
        var pending=service.assign(trip.id(),new TripService.Assignment(7,"driver"),dispatch);
        assertThat(pending.status()).isEqualTo("ASSIGNING");
        var request=json.readValue(db.sql("SELECT payload FROM outbox_event WHERE type='transport.assignment-requested'").query(String.class).single(),EventEnvelope.class);
        assertThat(request.aggregateId()).isEqualTo(Long.toString(trip.id()));
        assertThat(request.payload().path("passengers").asInt()).isEqualTo(2);
        var event=reply(trip.id(),request.aggregateVersion(),true,4);
        consumer.accept(event);consumer.accept(event);
        var assigned=trips.get(trip.id(),"employee");
        assertThat(assigned.status()).isEqualTo("ASSIGNED");
        assertThat(assigned.driverId()).isEqualTo("driver");
        assertThat(assigned.assignedAt()).isNotNull();
        assertThat(count("inbox_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(4);
    }

    @Test void rejectionCanRetryAndStaleRepliesCannotSettleNewAssignment() throws Exception {
        var trip=request();service.assign(trip.id(),new TripService.Assignment(7,"driver"),dispatch);
        long first=version(trip.id());
        consumer.accept(reply(trip.id(),first,false,4));
        assertThat(trips.get(trip.id(),"employee").status()).isEqualTo("REQUESTED");
        assertThat(trips.get(trip.id(),"employee").assignmentError()).isEqualTo("Vehicle busy");
        service.assign(trip.id(),new TripService.Assignment(7,"driver"),dispatch);
        consumer.accept(reply(trip.id(),first,true,4));
        assertThat(trips.get(trip.id(),"employee").status()).isEqualTo("ASSIGNING");
        consumer.accept(reply(trip.id(),version(trip.id()),true,4));
        assertThat(trips.get(trip.id(),"employee").status()).isEqualTo("ASSIGNED");
    }

    @Test void rejectedShuttleIsCancelledAndPendingAssignmentCannotBeCancelled() throws Exception {
        var shuttle=service.shuttle(new TripService.Shuttle(1,2,Instant.now().plusSeconds(3600),7,"driver"),dispatch);
        assertThatThrownBy(()->service.status(shuttle.id(),new TripService.Status("CANCELLED"),dispatch)).isInstanceOf(ApiException.class);
        consumer.accept(reply(shuttle.id(),version(shuttle.id()),false,4));
        assertThat(trips.get(shuttle.id(),"employee").status()).isEqualTo("CANCELLED");
        assertThat(trips.get(shuttle.id(),"employee").assignedAt()).isNull();
    }

    @Test void transitionsAndOwnershipAreEnforced() throws Exception {
        var trip=assigned();
        assertThatThrownBy(()->service.status(trip.id(),new TripService.Status("ARRIVED"),new Actor("other-driver","DRIVER"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.status(trip.id(),new TripService.Status("CANCELLED"),new Actor("outsider","EMPLOYEE"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.status(trip.id(),new TripService.Status("COMPLETED"),driver)).isInstanceOf(ApiException.class);
        service.status(trip.id(),new TripService.Status("ARRIVED"),driver);
        service.status(trip.id(),new TripService.Status("IN_PROGRESS"),driver);
        assertThatThrownBy(()->service.status(trip.id(),new TripService.Status("CANCELLED"),employee)).isInstanceOf(ApiException.class);
        service.status(trip.id(),new TripService.Status("COMPLETED"),driver);
        assertThatThrownBy(()->service.status(trip.id(),new TripService.Status("ARRIVED"),dispatch)).isInstanceOf(ApiException.class);
        assertThat(trips.list(new Actor("outsider","EMPLOYEE"))).isEmpty();
        assertThat(trips.list(new Actor("other-driver","DRIVER"))).isEmpty();
        assertThat(trips.list(employee)).extracting(Trip::id).containsExactly(trip.id());
    }

    @Test void twoRidersCannotWinTheLastShuttleSeat() throws Exception {
        var shuttle=service.shuttle(new TripService.Shuttle(1,2,Instant.now().plusSeconds(3600),7,"driver"),dispatch);
        consumer.accept(reply(shuttle.id(),version(shuttle.id()),true,1));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(String user:List.of("rider-a","rider-b")) attempts.add(executor.submit(()->{
                start.await();
                try { service.reserve(shuttle.id(),new TripService.Seats(1),new Actor(user,"EMPLOYEE"));return true; }
                catch(ApiException rejected) { return false; }
            }));
            start.countDown();
            int wins=0;for(var attempt:attempts) if(attempt.get(10,TimeUnit.SECONDS)) wins++;
            assertThat(wins).isEqualTo(1);
        }
        assertThat(trips.get(shuttle.id(),"dispatch").passengers()).isEqualTo(1);
    }

    @Test void shuttleReservationsCloseWhenBoardingStartsAndCannotLeaveEarly() throws Exception {
        var shuttle=service.shuttle(new TripService.Shuttle(1,2,Instant.now().plusSeconds(3600),7,"driver"),dispatch);
        consumer.accept(reply(shuttle.id(),version(shuttle.id()),true,4));
        service.reserve(shuttle.id(),new TripService.Seats(2),employee);
        assertThatThrownBy(()->service.reserve(shuttle.id(),new TripService.Seats(1),employee)).isInstanceOf(ApiException.class);
        service.status(shuttle.id(),new TripService.Status("ARRIVED"),driver);
        assertThatThrownBy(()->service.cancelReservation(shuttle.id(),employee)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.reserve(shuttle.id(),new TripService.Seats(1),new Actor("other","EMPLOYEE"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.status(shuttle.id(),new TripService.Status("IN_PROGRESS"),driver)).isInstanceOf(ApiException.class);
    }

    @Test void businessAndOutboxRecordsRollbackTogether() {
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{request();throw new IllegalStateException("simulated failure before commit");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("trip")).isZero();assertThat(count("reservation")).isZero();assertThat(count("outbox_event")).isZero();
    }

    @Test void malformedConfirmationRollsBackInboxAndLeavesPendingTrip() throws Exception {
        var trip=request();service.assign(trip.id(),new TripService.Assignment(7,"driver"),dispatch);
        assertThatThrownBy(()->consumer.accept(reply(trip.id(),version(trip.id()),true,1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("inbox_event")).isZero();
        assertThat(trips.get(trip.id(),"employee").status()).isEqualTo("ASSIGNING");
    }

    @Test void envelopeAggregateMustMatchPayload() throws Exception {
        var trip=request();service.assign(trip.id(),new TripService.Assignment(7,"driver"),dispatch);
        var valid=json.readValue(reply(trip.id(),version(trip.id()),true,4),EventEnvelope.class);
        var invalid=new EventEnvelope(valid.id(),valid.type(),1,"999999",valid.aggregateVersion(),valid.occurredAt(),valid.actor(),valid.payload());
        assertThatThrownBy(()->consumer.accept(json.writeValueAsString(invalid))).isInstanceOf(IllegalArgumentException.class);
        assertThat(trips.get(trip.id(),"employee").status()).isEqualTo("ASSIGNING");
    }

    @Test void httpRequiresAuthenticationRolesAndValidInput() throws Exception {
        mvc.perform(get("/api/trips")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/trips").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"originId\":1,\"destinationId\":2,\"seats\":1,\"purpose\":\"Inspection\"}")).andExpect(status().isForbidden());
        var trip=request();
        mvc.perform(post("/api/trips/"+trip.id()+"/assign").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"vehicleId\":7,\"driverId\":\"driver\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/trips").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"originId\":1,\"destinationId\":2,\"seats\":0,\"purpose\":\"Inspection\"}")).andExpect(status().isBadRequest());
    }

    private Trip request() { return service.request(new TripService.Request(1,2,2,"Inspection"),employee); }
    private Trip assigned() throws Exception { var trip=request();service.assign(trip.id(),new TripService.Assignment(7,"driver"),dispatch);consumer.accept(reply(trip.id(),version(trip.id()),true,4));return trips.get(trip.id(),"employee"); }
    private long version(long id) { return db.sql("SELECT assignment_version FROM trip WHERE id=?").param(id).query(Long.class).single(); }
    private long count(String table) { return db.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single(); }
    private String reply(long id,long version,boolean confirmed,int capacity) throws Exception {
        var payload=json.createObjectNode().put("tripId",id);
        if(confirmed) payload.put("vehicleId",7).put("vehicleLabel","BUS-7").put("capacity",capacity).put("driverId","driver").put("driverName","Driver");
        else payload.put("reason","Vehicle busy");
        return json.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(),confirmed?"fleet.assignment-confirmed":"fleet.assignment-rejected",1,Long.toString(id),version,Instant.now(),"dispatch",payload));
    }
}
