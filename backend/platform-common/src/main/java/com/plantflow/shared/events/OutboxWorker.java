package com.plantflow.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="plantflow.messaging.enabled",havingValue="true",matchIfMissing=true)
public class OutboxWorker {
    private static final Logger log=LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxPublisher publisher;
    public OutboxWorker(OutboxPublisher publisher) { this.publisher=publisher; }
    @Scheduled(fixedDelayString="${plantflow.messaging.poll-ms:1000}",initialDelay=3000)
    public void tick() {
        try { int count=publisher.publishBatch();if(count>0) log.debug("Broker confirmed {} events",count); }
        catch(RuntimeException e) { log.warn("Outbox publish rolled back; persisted events will retry: {}",e.getClass().getSimpleName()); }
    }
}
