package com.idp.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

final class TestEvents {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private TestEvents() {
    }

    static EventEnvelope accesoRevocado(UUID tenantId) {
        return new EventEnvelope(UUID.randomUUID(), "acceso.revocado", 1, Instant.parse("2026-01-01T10:00:00Z"),
            tenantId, UUID.randomUUID(), MAPPER.createObjectNode().put("subjectId", "user-7"));
    }
}
