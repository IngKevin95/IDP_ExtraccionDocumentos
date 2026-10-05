package com.idp.document.service;

import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.domain.InvalidTransitionException;
import com.idp.document.infra.DocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/** Transiciones del estado canonico con control optimista y metrica de tiempo entre estados. */
@Service
public class DocumentStateMachine {

    private final DocumentRepository repo;
    private final MeterRegistry meters;
    private final Clock clock;

    public DocumentStateMachine(DocumentRepository repo, MeterRegistry meters, Clock clock) {
        this.repo = repo;
        this.meters = meters;
        this.clock = clock;
    }

    /** Debe ejecutarse dentro de la transaccion de negocio. Devuelve el documento en su nuevo estado. */
    public DocumentRecord transition(DocumentRecord doc, DocumentStatus to) {
        if (!doc.status().canTransitionTo(to)) {
            throw new InvalidTransitionException(doc.status(), to);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (repo.updateStatus(doc.tenantId(), doc.id(), doc.status(), to, now) != 1) {
            throw new IllegalStateException("El estado del documento cambio concurrentemente");
        }
        Timer.builder("idp_document_transition_time_seconds").tag("from", doc.status().name())
                .tag("to", to.name()).register(meters)
                .record(Duration.between(doc.updatedAt(), now).abs());
        return doc.withStatus(to, now);
    }
}
