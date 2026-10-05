package com.idp.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** Preservacion legal de un documento (documentId) o de todo el tenant (documentId nulo). */
public record LegalHoldRecord(
        UUID id,
        UUID tenantId,
        UUID documentId,
        String reason,
        String appliedBy,
        Status status,
        Instant createdAt,
        String releasedBy,
        Instant releasedAt) {

    public enum Status { ACTIVE, RELEASED }
}
