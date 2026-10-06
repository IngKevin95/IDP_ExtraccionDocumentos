package com.idp.document.service;

import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.domain.PageArtifact;
import com.idp.security.Roles;
import com.idp.document.infra.ArtifactVault;
import com.idp.document.infra.DocumentRepository;
import com.idp.document.infra.DomainEvents;
import com.idp.document.service.Exceptions.ConflictException;
import com.idp.document.service.Exceptions.DocumentNotFoundException;
import com.idp.tenant.context.LegalHoldGate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Aprobacion por Data Steward (Regla 4) y purga por Habeas Data (SEC-022). */
@Service
public class DocumentLifecycleService {

    private final DocumentRepository repo;
    private final DocumentStateMachine states;
    private final DomainEvents events;
    private final ArtifactVault vault;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LegalHoldGate holds;

    public DocumentLifecycleService(DocumentRepository repo, DocumentStateMachine states, DomainEvents events,
                                    ArtifactVault vault, TransactionTemplate tx, Clock clock, LegalHoldGate holds) {
        this.holds = holds;
        this.repo = repo;
        this.states = states;
        this.events = events;
        this.vault = vault;
        this.tx = tx;
        this.clock = clock;
    }

    /** El Data Steward debe ser distinto de quien cargo el documento. */
    public DocumentRecord approveConfidential(Caller caller, UUID documentId) {
        DocumentRecord doc = repo.findById(caller.tenantId(), documentId).filter(caller::canView)
                .orElseThrow(DocumentNotFoundException::new);
        if (doc.status() != DocumentStatus.APROBADO_PENDIENTE_STEWARD) {
            throw new ConflictException("DOC_INVALID_STATE", "El documento no esta pendiente de aprobacion");
        }
        if (caller.userId().equals(doc.uploadedBy())) {
            throw new AccessDeniedException("El Data Steward no puede ser quien cargo el documento");
        }
        return tx.execute(s -> {
            DocumentRecord approved = states.transition(doc, DocumentStatus.APROBADO);
            repo.setApprovedBy(approved.tenantId(), approved.id(), Roles.DATA_STEWARD);
            events.aprobada(approved, Roles.DATA_STEWARD);
            return approved;
        });
    }

    /**
     * Borra primero los binarios (si falla, nada cambio y se puede reintentar) y luego, en una transaccion,
     * el tombstone en BD y el evento documento.purgado.
     */
    public void purge(Caller caller, UUID documentId) {
        if (!caller.has(Roles.TENANT_ADMIN)) {
            throw new AccessDeniedException("Rol insuficiente");
        }
        DocumentRecord doc = repo.findById(caller.tenantId(), documentId).orElseThrow(DocumentNotFoundException::new);
        if (holds.isHeld(doc.tenantId(), doc.id())) {
            throw new ConflictException("DOC_LEGAL_HOLD", "El documento esta bajo legal hold");
        }
        List<PageArtifact> artifacts = repo.artifacts(doc.id());
        vault.delete(doc.tenantId(), doc.objectStoreKey());
        for (PageArtifact a : artifacts) {
            vault.delete(doc.tenantId(), a.objectStoreKey());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        tx.executeWithoutResult(s -> {
            repo.deleteArtifacts(doc.id());
            if (repo.tombstone(doc.tenantId(), doc.id(), now) != 1) {
                throw new DocumentNotFoundException();
            }
            events.purgado(doc, now.toInstant());
        });
    }
}
