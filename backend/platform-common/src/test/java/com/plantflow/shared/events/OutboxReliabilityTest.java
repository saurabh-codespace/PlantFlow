package com.plantflow.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=OutboxReliabilityTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.datasource.url=jdbc:h2:mem:outbox_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.flyway.locations=classpath:db/common",
    "plantflow.messaging.enabled=false","logging.level.org.springframework=INFO"})
class OutboxReliabilityTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=RabbitAutoConfiguration.class)
    @Import({Outbox.class,Inbox.class})
    static class Config {
        @Bean OutboxPublisher publisher(JdbcClient db,RabbitTemplate rabbit) { return new OutboxPublisher(db,rabbit); }
    }
    @Autowired Outbox outbox;
    @Autowired Inbox inbox;
    @Autowired OutboxPublisher publisher;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean RabbitTemplate rabbit;
    TransactionTemplate tx;

    @BeforeEach void clean() {
        tx=new TransactionTemplate(manager);reset(rabbit);
        db.sql("DELETE FROM outbox_event").update();db.sql("DELETE FROM inbox_event").update();
    }

    @Test void acknowledgedPersistentJsonMessageIsMarkedPublishedAndNotSentAgain() throws Exception {
        seed(1);acknowledge();
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(pending()).isZero();
        assertThat(publisher.publishBatch()).isZero();
        var message=ArgumentCaptor.forClass(Message.class);var correlation=ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit,times(1)).send(eq("plantflow.events"),eq("transport.trip-changed"),message.capture(),correlation.capture());
        var envelope=json.readValue(new String(message.getValue().getBody(),StandardCharsets.UTF_8),EventEnvelope.class);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo(envelope.id()).isEqualTo(correlation.getValue().getId());
        assertThat(envelope.payload().path("tripId").asInt()).isEqualTo(1);
    }

    @Test void nackAfterFirstAckRollsBackWholeBatchAndRetryPreservesEventIds() {
        seed(2);
        var sentIds=new ArrayList<String>();var calls=new AtomicInteger();
        doAnswer(call->{var correlation=call.getArgument(3,CorrelationData.class);sentIds.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(calls.incrementAndGet()==1,"simulated nack"));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThatThrownBy(publisher::publishBatch).isInstanceOf(IllegalStateException.class);
        assertThat(pending()).isEqualTo(2);
        reset(rabbit);acknowledge();
        assertThat(publisher.publishBatch()).isEqualTo(2);
        var retried=ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit,times(2)).send(anyString(),anyString(),any(Message.class),retried.capture());
        assertThat(retried.getAllValues()).extracting(CorrelationData::getId).containsExactlyElementsOf(sentIds);
        assertThat(pending()).isZero();
    }

    @Test void ackWithUnroutableReturnDoesNotMarkPublished() {
        seed(1);
        doAnswer(call->{var correlation=call.getArgument(3,CorrelationData.class);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2,Message.class),312,"NO_ROUTE","plantflow.events","transport.trip-changed"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThatThrownBy(publisher::publishBatch).isInstanceOf(IllegalStateException.class).hasMessageContaining("route/confirm");
        assertThat(pending()).isEqualTo(1);
    }

    @Test void missingConfirmationTimesOutAndRetainsPendingEvent() {
        seed(1);
        assertThatThrownBy(publisher::publishBatch).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(pending()).isEqualTo(1);
    }

    @Test void disconnectedBrokerLeavesPersistedEventPending() {
        seed(1);
        doThrow(new AmqpConnectException(new java.net.ConnectException("offline"))).when(rabbit)
            .send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThatThrownBy(publisher::publishBatch).isInstanceOf(AmqpConnectException.class);
        assertThat(pending()).isEqualTo(1);
    }

    @Test void outboxRequiresBusinessTransactionAndRollsBackWithIt() {
        assertThatThrownBy(()->outbox.publish("transport.trip-changed","1",1,"test",Map.of("tripId",1))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            outbox.publish("transport.trip-changed","1",1,"test",Map.of("tripId",1));throw new IllegalStateException("business rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.sql("SELECT COUNT(*) FROM outbox_event").query(Long.class).single()).isZero();
        verifyNoInteractions(rabbit);
    }

    @Test void inboxDeduplicatesOnlyCommittedEventsAndRequiresTransaction() {
        var event=new EventEnvelope(UUID.randomUUID().toString(),"transport.trip-changed",1,"1",1,Instant.now(),"test",json.createObjectNode().put("tripId",1));
        assertThatThrownBy(()->inbox.first(event)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{assertThat(inbox.first(event)).isTrue();throw new IllegalStateException("consumer rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.sql("SELECT COUNT(*) FROM inbox_event").query(Long.class).single()).isZero();
        assertThat(tx.<Boolean>execute(status->inbox.first(event))).isTrue();
        assertThat(tx.<Boolean>execute(status->inbox.first(event))).isFalse();
        assertThat(db.sql("SELECT COUNT(*) FROM inbox_event").query(Long.class).single()).isEqualTo(1);
    }

    private void acknowledge() {
        doAnswer(call->{call.getArgument(3,CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    private void seed(int count) {
        tx.executeWithoutResult(status->{for(int i=1;i<=count;i++) outbox.publish("transport.trip-changed",Integer.toString(i),1,"test",Map.of("tripId",i));});
    }
    private long pending() { return db.sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL").query(Long.class).single(); }
}
