package com.idp.audit.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.events.EventEnvelope;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compuerta de retencion (AC-08, SEC-017/SEC-042): ante una solicitud de expurgo (o expiracion de politica)
 * sobre un documento o las KEK del tenant, rechaza si hay legal hold activo. El rechazo queda encadenado en la
 * hash-chain como entrada {@code legalhold.purga_rechazada} (evento interno, sin PII).
 */
@Service
public class RetentionService {

    public static final String REJECTED_EVENT = "legalhold.purga_rechazada";

    /** Decision sobre la destruccion solicitada. */
    public record PurgeDecision(boolean allowed, String reason) {}

    private final AuditRepository repo;
    private final AuditIngestionService ingestion;
    private final Clock clock;

    public RetentionService(AuditRepository repo, AuditIngestionService ingestion, Clock clock) {
        this.repo = repo;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    /**
     * @param documentId documento a expurgar; nulo para destruccion de KEK/datos del tenant completo
     */
    @Transactional
    public PurgeDecision checkPurge(UUID tenantId, UUID documentId, String requestedBy) {
        boolean held = documentId == null
                ? !repo.activeHolds(tenantId).isEmpty()
                : repo.isHeld(tenantId, documentId);
        if (!held) {
            return new PurgeDecision(true, null);
        }
        ObjectNode payload = CanonicalJson.mapper().createObjectNode();
        if (documentId != null) {
            payload.put("documentId", documentId.toString());
        }
        payload.put("requestedBy", requestedBy);
        payload.put("reason", "LEGAL_HOLD_ACTIVE");
        ingestion.ingest(new EventEnvelope(UUID.randomUUID(), REJECTED_EVENT, 1, clock.instant(), tenantId,
                UUID.randomUUID(), payload));
        return new PurgeDecision(false, "LEGAL_HOLD_ACTIVE");
    }
}
