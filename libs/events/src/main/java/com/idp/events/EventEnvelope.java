package com.idp.events;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    UUID tenantId,
    UUID correlationId,
    JsonNode payload
) {}
