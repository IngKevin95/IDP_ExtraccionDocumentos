package com.idp.document.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.document.domain.DocumentRecord;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Construye y publica por outbox los eventos del document-service (claim-check, sin PII). Debe invocarse
 * dentro de la transaccion que cambia el estado. correlationId = documentId (traza estable por documento).
 */
@Component
public class DomainEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboxPublisher outbox;

    public DomainEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void recibido(DocumentRecord d) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", d.id().toString());
        p.put("hashSha256", d.hashSha256());
        p.put("typology", d.typology());
        p.put("version", d.version());
        p.put("classification", d.classification().name());
        p.put("objectStoreKey", d.objectStoreKey());
        emit("documento.recibido", d, p);
    }

    public void renderizado(DocumentRecord d, List<String> pageKeys) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", d.id().toString());
        p.put("pageCount", pageKeys.size());
        var arr = p.putArray("pageArtifactKeys");
        pageKeys.forEach(arr::add);
        emit("documento.renderizado", d, p);
    }

    public void rechazado(DocumentRecord d, String reasonCode) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", d.id().toString());
        p.put("reasonCode", reasonCode);
        emit("documento.rechazado", d, p);
    }

    public void extraccionSolicitada(DocumentRecord d) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", d.id().toString());
        p.put("typology", d.typology());
        emit("extraccion.solicitada", d, p);
    }

    public void aprobada(DocumentRecord d, String approvedBy) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", d.id().toString());
        p.put("approvedBy", approvedBy);
        if (d.typology() != null) {
            p.put("typology", d.typology());
        }
        emit("extraccion.aprobada", d, p);
    }

    public void purgado(DocumentRecord d, Instant purgedAt) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", d.id().toString());
        p.put("purgedAt", purgedAt.toString());
        emit("documento.purgado", d, p);
    }

    private void emit(String type, DocumentRecord d, ObjectNode payload) {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(),
                UUID.fromString(d.tenantId()), d.id(), payload);
        outbox.publish(d.id().toString(), e);
    }
}
