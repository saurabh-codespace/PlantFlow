package com.plantflow.shared.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record EventEnvelope(String id,String type,int schemaVersion,String aggregateId,long aggregateVersion,
    Instant occurredAt,String actor,JsonNode payload) {}
