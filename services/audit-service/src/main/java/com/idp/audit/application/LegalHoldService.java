package com.idp.audit.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.Exceptions.NotFoundException;
import com.idp.audit.domain.LegalHoldRecord;
import com.idp.audit.domain.WormAnchor;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.events.EventEnvelope;
import com.idp.storage.ImmutableStore;
import com.idp.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preservacion legal (SEC-042, AC-08): persiste el hold, extiende la proteccion WORM (legal hold del
 * ImmutableStore) a los anclajes afectados y emite legalhold.aplicado / legalhold.liberado. Mientras haya un
 * hold activo, la destruccion de datos y de KEK se rechaza (ver {@link RetentionService}).
 */
@Service
public class LegalHoldService {

    private final AuditRepository repo;
    private final ImmutableStore store;
    private final AuditEventPublisher publisher;
    private final Clock clock;

    public LegalHoldService(AuditRepository repo, ImmutableStore store, AuditEventPublisher publisher,
                            Clock clock) {
        this.repo = repo;
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Aplica el hold (idempotente por alcance). {@code documentId} nulo cubre el tenant completo. */
    @Transactional
    public LegalHoldRecord apply(UUID tenantId, UUID documentId, String reason, String reasonCode,
                                 String appliedBy) {
        var existing = repo.activeHoldWithScope(tenantId, documentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LegalHoldRecord hold = new LegalHoldRecord(UUID.randomUUID(), tenantId, documentId, reason, appliedBy,
                LegalHoldRecord.Status.ACTIVE, now, null, null);
        repo.insertHold(hold);
        TenantId tenant = new TenantId(tenantId.toString());
        for (WormAnchor a : affectedAnchors(tenantId, documentId)) {
            store.applyLegalHold(tenant, a.fileUri());
        }
        ObjectNode payload = CanonicalJson.mapper().createObjectNode();
        payload.put("holdId", hold.id().toString());
        if (documentId != null) {
            payload.put("documentId", documentId.toString());
        }
        payload.put("reasonCode", reasonCode);
        payload.put("appliedBy", appliedBy);
        publisher.publish(new EventEnvelope(UUID.randomUUID(), "legalhold.aplicado", 1, now, tenantId,
                UUID.randomUUID(), payload));
        return hold;
    }

    /** Libera el hold activo de ese alcance y retira el legal hold WORM de lo que ningun otro hold cubre. */
    @Transactional
    public LegalHoldRecord release(UUID tenantId, UUID documentId, String releasedBy) {
        LegalHoldRecord hold = repo.activeHoldWithScope(tenantId, documentId)
                .orElseThrow(() -> new NotFoundException("No hay legal hold activo para ese alcance."));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repo.releaseHold(hold.id(), releasedBy, now);
        List<LegalHoldRecord> remaining = repo.activeHolds(tenantId);
        TenantId tenant = new TenantId(tenantId.toString());
        for (WormAnchor a : affectedAnchors(tenantId, documentId)) {
            if (!covered(tenantId, a, remaining)) {
                store.removeLegalHold(tenant, a.fileUri());
            }
        }
        ObjectNode payload = CanonicalJson.mapper().createObjectNode();
        payload.put("holdId", hold.id().toString());
        if (documentId != null) {
            payload.put("documentId", documentId.toString());
        }
        payload.put("releasedBy", releasedBy);
        publisher.publish(new EventEnvelope(UUID.randomUUID(), "legalhold.liberado", 1, now, tenantId,
                UUID.randomUUID(), payload));
        return new LegalHoldRecord(hold.id(), tenantId, documentId, hold.reason(), hold.appliedBy(),
                LegalHoldRecord.Status.RELEASED, hold.createdAt(), releasedBy, now);
    }

    private List<WormAnchor> affectedAnchors(UUID tenantId, UUID documentId) {
        List<WormAnchor> all = repo.anchors(tenantId);
        if (documentId == null) {
            return all;
        }
        List<AuditEntry> docEntries = repo.findByDocument(tenantId, documentId);
        return all.stream().filter(a -> docEntries.stream()
                .anyMatch(e -> e.sequenceId() >= a.startSequenceId() && e.sequenceId() <= a.endSequenceId()))
                .toList();
    }

    private boolean covered(UUID tenantId, WormAnchor a, List<LegalHoldRecord> holds) {
        for (LegalHoldRecord h : holds) {
            if (h.documentId() == null) {
                return true;
            }
            boolean inAnchor = repo.findByDocument(tenantId, h.documentId()).stream()
                    .anyMatch(e -> e.sequenceId() >= a.startSequenceId() && e.sequenceId() <= a.endSequenceId());
            if (inAnchor) {
                return true;
            }
        }
        return false;
    }
}
