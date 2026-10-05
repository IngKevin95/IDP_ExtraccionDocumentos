package com.idp.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Registro inmutable de la cadena de hash de un tenant. El payload es claim-check, sin PII (SEC-050). */
public record AuditEntry(
        UUID id,
        long sequenceId,
        UUID tenantId,
        UUID eventId,
        UUID correlationId,
        UUID documentId,
        String eventType,
        String actorId,
        Instant occurredAt,
        JsonNode payload,
        String payloadSha256,
        String currentHash,
        String previousHash,
        boolean wormAnchored,
        Instant createdAt) {
}
