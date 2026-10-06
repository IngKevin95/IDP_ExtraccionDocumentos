package com.idp.security.breakglass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

public class BreakGlassManager {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassManager.class);
    private final OutboxPublisher publisher;
    private final ObjectMapper mapper;

    public BreakGlassManager(OutboxPublisher publisher, ObjectMapper mapper) {
        this.publisher = publisher;
        this.mapper = mapper;
    }

    public void grant(UUID tenantId, String subjectId, String approvedBy, Instant expiresAt) {
        if (subjectId.equals(approvedBy)) {
            throw new IllegalArgumentException("Four-eyes principle violated: requester cannot be approver");
        }
        
        LOG.error("ALERTA CRITICA: Break-glass otorgado en tenant {} para el usuario {} aprobado por {}", tenantId, subjectId, approvedBy);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.put("approvedBy", approvedBy);
        payload.put("expiresAt", expiresAt.toString());

        EventEnvelope event = new EventEnvelope(
            UUID.randomUUID(),
            "breakglass.otorgado",
            1,
            Instant.now(),
            tenantId,
            UUID.randomUUID(),
            payload
        );

        publisher.publish(tenantId.toString(), event);
    }

    public void expire(UUID tenantId, String subjectId) {
        LOG.error("ALERTA CRITICA: Break-glass expirado en tenant {} para el usuario {}", tenantId, subjectId);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("subjectId", subjectId);

        EventEnvelope event = new EventEnvelope(
            UUID.randomUUID(),
            "breakglass.expirado",
            1,
            Instant.now(),
            tenantId,
            UUID.randomUUID(),
            payload
        );

        publisher.publish(tenantId.toString(), event);
    }
}
