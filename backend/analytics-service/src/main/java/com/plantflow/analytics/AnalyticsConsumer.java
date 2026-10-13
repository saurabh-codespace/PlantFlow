package com.plantflow.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantflow.shared.events.EventEnvelope;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="plantflow.messaging.enabled",havingValue="true",matchIfMissing=true)
public class AnalyticsConsumer {
    private final ObjectMapper json;
    private final AnalyticsProjection projection;
    public AnalyticsConsumer(ObjectMapper json,AnalyticsProjection projection) { this.json=json;this.projection=projection; }
    @RabbitListener(queues="plantflow.analytics")
    public void receive(String raw) throws Exception { projection.apply(json.readValue(raw,EventEnvelope.class)); }
}
