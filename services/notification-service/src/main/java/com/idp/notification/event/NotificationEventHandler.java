package com.idp.notification.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.domain.WebhookEvent;
import com.idp.notification.domain.WebhookSubscription;
import com.idp.notification.store.WebhookRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Convierte un evento de dominio en una entrega PENDIENTE por cada suscripcion activa del tenant del evento que
 * este suscrita a su tipo. Corre dentro de la transaccion del consumidor idempotente, con el tenant del evento en
 * el contexto (silo correcto). El cuerpo es claim-check: ids y estado, nunca datos extraidos (SEC-050).
 */
@Component
public class NotificationEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationEventHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern REASON_CODE = java.util.regex.Pattern.compile("^[A-Z_]{1,40}$");

    private final WebhookRepository repository;
    private final Clock clock;

    public NotificationEventHandler(WebhookRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void handle(EventEnvelope event) {
        WebhookEvent type = WebhookEvent.fromWire(event.eventType()).orElse(null);
        if (type == null) {
            return;
        }
        if (event.payload().path("blindSample").asBoolean(false)) {
            // Revision ciega de calidad: es una medicion interna, no un resultado para el integrador.
            return;
        }
        UUID documentId = UUID.fromString(event.payload().path("documentId").asText());
        Instant now = clock.instant();
        int created = 0;
        for (WebhookSubscription sub : repository.activeSubscriptions(event.tenantId())) {
            if (!sub.subscribedTo(type)) {
                continue;
            }
            UUID deliveryId = UUID.randomUUID();
            WebhookDelivery delivery = new WebhookDelivery(deliveryId, event.tenantId(), sub.id(), documentId,
                event.eventId(), event.correlationId(), type.wireName(), body(deliveryId, type, event, documentId),
                DeliveryStatus.PENDIENTE, 0, now, null, null, null, null, null, now);
            if (repository.insertDelivery(delivery)) {
                created++;
            }
        }
        LOG.debug("Evento {} genero {} entregas", type.wireName(), created);
    }

    /** El motivo de rechazo viene de otro servicio: solo se copia al webhook si es un codigo cerrado. */
    static String safeReasonCode(String reasonCode) {
        return reasonCode != null && REASON_CODE.matcher(reasonCode).matches() ? reasonCode : "UNSPECIFIED";
    }

    /** Cuerpo saliente: id estable de la entrega (clave de idempotencia del receptor), tipo, documento y estado. */
    static String body(UUID deliveryId, WebhookEvent type, EventEnvelope event, UUID documentId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", deliveryId.toString());
        n.put("eventType", type.wireName());
        n.put("occurredAt", event.occurredAt().toString());
        n.put("documentId", documentId.toString());
        switch (type) {
            case EXTRACCION_APROBADA -> n.put("status", "APROBADA");
            case DOCUMENTO_RECHAZADO -> {
                n.put("status", "RECHAZADO");
                n.put("reasonCode", safeReasonCode(event.payload().path("reasonCode").asText()));
            }
            case REVISION_COMPLETADA -> n.put("status", "REVISION_" + event.payload().path("action").asText());
            default -> throw new IllegalStateException("Evento no notificable");
        }
        return n.toString();
    }
}
