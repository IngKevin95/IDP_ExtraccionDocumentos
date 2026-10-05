package com.idp.document.service;

import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.infra.DocumentRepository;
import com.idp.document.infra.DomainEvents;
import com.idp.events.EventEnvelope;
import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reacciona a los eventos del pipeline (extraccion.completada, extraccion.requiere_revision,
 * revision.completada). Se invoca dentro de la transaccion del consumidor idempotente, con el tenant del
 * evento ya fijado en el contexto. Los eventos fuera de secuencia o de documentos inexistentes se ignoran.
 */
@Service
public class PipelineEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineEventHandler.class);

    private final DocumentRepository repo;
    private final DocumentStateMachine states;
    private final DomainEvents events;
    private final RoleAssignmentVerifier roles;
    private final MeterRegistry meters;

    public PipelineEventHandler(DocumentRepository repo, DocumentStateMachine states, DomainEvents events,
                                RoleAssignmentVerifier roles, MeterRegistry meters) {
        this.repo = repo;
        this.states = states;
        this.events = events;
        this.roles = roles;
        this.meters = meters;
    }

    public void handle(EventEnvelope e) {
        switch (e.eventType()) {
            case "extraccion.completada" -> onExtractionCompleted(e);
            case "extraccion.requiere_revision" -> onRequiresReview(e);
            case "revision.completada" -> onReviewCompleted(e);
            default -> LOG.debug("Evento {} ignorado", e.eventType());
        }
    }

    void onExtractionCompleted(EventEnvelope e) {
        load(e).filter(d -> expect(d, DocumentStatus.EN_EXTRACCION, e)).ifPresent(d -> approve(d, "AUTO_STP"));
    }

    void onRequiresReview(EventEnvelope e) {
        load(e).filter(d -> expect(d, DocumentStatus.EN_EXTRACCION, e))
                .ifPresent(d -> states.transition(d, DocumentStatus.EN_REVISION));
    }

    void onReviewCompleted(EventEnvelope e) {
        load(e).filter(d -> expect(d, DocumentStatus.EN_REVISION, e)).filter(d -> reviewersValid(d, e))
                .ifPresent(d -> {
                    String action = e.payload().path("action").asText("");
                    if ("APROBADO".equals(action)) {
                        approve(d, "HUMAN_REVIEWER");
                    } else {
                        // El catalogo de reasonCode no define motivo para rechazo humano: no se emite
                        // documento.rechazado.
                        states.transition(d, DocumentStatus.RECHAZADO);
                    }
                });
    }

    /**
     * El evento solo prueba que alguien lo publico: el revisor (y el segundo aprobador si hubo correccion critica) se
     * revalidan contra role_assignment antes de aprobar o rechazar. Si no, se ignora el evento y se alerta.
     */
    private boolean reviewersValid(DocumentRecord d, EventEnvelope e) {
        JsonNode p = e.payload();
        String tenant = e.tenantId().toString();
        String reviewer = p.path("reviewerId").asText("");
        boolean critical = p.path("criticalCorrection").asBoolean(false);
        String second = p.path("secondReviewerId").asText("");
        boolean ok = !reviewer.isBlank() && roles.hasRole(tenant, reviewer, Roles.REVISOR);
        if (ok && critical) {
            ok = !second.isBlank() && !second.equals(reviewer) && roles.hasRole(tenant, second, Roles.REVISOR);
        }
        if (!ok) {
            LOG.error("ALERTA revision.completada ignorada: revisor sin rol vigente o aprobador invalido "
                    + "(documento {}, tenant {})", d.id(), tenant);
            meters.counter("idp_document_review_event_rejected_total").increment();
        }
        return ok;
    }

    /** Altamente Confidencial no llega a APROBADO sin Data Steward distinto del cargador (Regla 4). */
    private void approve(DocumentRecord d, String approvedBy) {
        if (d.classification() == Classification.ALTAMENTE_CONFIDENCIAL) {
            states.transition(d, DocumentStatus.APROBADO_PENDIENTE_STEWARD);
            return;
        }
        DocumentRecord approved = states.transition(d, DocumentStatus.APROBADO);
        events.aprobada(approved, approvedBy);
    }

    private Optional<DocumentRecord> load(EventEnvelope e) {
        UUID documentId = UUID.fromString(e.payload().path("documentId").asText());
        Optional<DocumentRecord> doc = repo.findById(e.tenantId().toString(), documentId);
        if (doc.isEmpty()) {
            LOG.warn("Evento {} para documento inexistente o purgado", e.eventType());
        }
        return doc;
    }

    private static boolean expect(DocumentRecord d, DocumentStatus expected, EventEnvelope e) {
        if (d.status() != expected) {
            LOG.warn("Evento {} ignorado: documento {} en estado {} (se esperaba {})", e.eventType(), d.id(),
                    d.status(), expected);
            return false;
        }
        return true;
    }
}
