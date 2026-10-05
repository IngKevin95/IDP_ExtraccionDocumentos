package com.idp.review.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import com.idp.review.domain.ReviewTask;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Publica por outbox el evento revision.completada (claim-check, sin PII: solo UUIDs y subjects). Debe invocarse
 * dentro de la transaccion que cambia el estado de la tarea. La particion es el documentId.
 */
@Component
public class ReviewEvents {

    public static final String APROBADO = "APROBADO";
    public static final String RECHAZADO = "RECHAZADO";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboxPublisher outbox;

    public ReviewEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /**
     * @param secondReviewerId segundo aprobador, solo con criticalCorrection (cuatro ojos); null en otro caso
     */
    public void completada(ReviewTask task, String action, String reviewerId, String secondReviewerId,
                           boolean criticalCorrection) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", task.documentId().toString());
        p.put("taskId", task.id().toString());
        p.put("action", action);
        p.put("reviewerId", reviewerId);
        if (secondReviewerId != null) {
            p.put("secondReviewerId", secondReviewerId);
        }
        p.put("criticalCorrection", criticalCorrection);
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), "revision.completada", 1, Instant.now(),
                UUID.fromString(task.tenantId()), task.correlationId(), p);
        outbox.publish(task.documentId().toString(), e);
    }
}
