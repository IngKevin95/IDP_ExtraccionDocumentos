package com.idp.notification.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import com.idp.notification.domain.FailureReason;
import com.idp.notification.domain.WebhookDelivery;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Publica por outbox los resultados de entrega (claim-check: solo ids, contadores y codigo de motivo; SEC-050).
 * Debe invocarse dentro de la transaccion que actualiza webhook_delivery.
 */
@Component
public class DomainEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboxPublisher outbox;
    private final Clock clock;

    public DomainEvents(OutboxPublisher outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public void entregado(WebhookDelivery d, int attempts, long latencyMs) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("webhookId", d.webhookId().toString());
        p.put("documentId", d.documentId().toString());
        p.put("attempts", attempts);
        p.put("latencyMs", latencyMs);
        emit("webhook.entregado", d, p);
    }

    public void fallido(WebhookDelivery d, FailureReason reason, int attempts) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("webhookId", d.webhookId().toString());
        p.put("documentId", d.documentId().toString());
        p.put("reasonCode", reason.name());
        p.put("attempts", attempts);
        emit("webhook.fallido", d, p);
    }

    private void emit(String type, WebhookDelivery d, ObjectNode payload) {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, 1, clock.instant(), d.tenantId(),
            d.correlationId(), payload);
        outbox.publish(d.documentId().toString(), e);
    }
}
