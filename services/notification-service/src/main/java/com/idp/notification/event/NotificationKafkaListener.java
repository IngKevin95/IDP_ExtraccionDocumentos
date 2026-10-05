package com.idp.notification.event;

import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.notification.domain.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume el topico de dominio y procesa solo los eventos notificables, con consumo idempotente (processed_event)
 * y validacion contra el JSON Schema del contrato. Los fallos van al error handler (reintentos y DLT).
 */
@Component
public class NotificationKafkaListener {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationKafkaListener.class);

    private final IdempotentEventConsumer consumer;
    private final NotificationEventHandler handler;
    private final EventSerde serde;

    public NotificationKafkaListener(IdempotentEventConsumer consumer, NotificationEventHandler handler,
                                     EventSerde serde) {
        this.consumer = consumer;
        this.handler = handler;
        this.serde = serde;
    }

    @KafkaListener(topics = "${idp.topic:dominio.documentos}", groupId = "${spring.application.name}")
    public void onMessage(String json) {
        if (WebhookEvent.fromWire(eventType(json)).isEmpty()) {
            return;
        }
        IdempotentEventConsumer.Result r = consumer.consume(json, handler::handle);
        if (r == IdempotentEventConsumer.Result.DUPLICATE) {
            LOG.debug("Evento duplicado ignorado");
        }
    }

    private String eventType(String json) {
        try {
            return serde.mapper().readTree(json).path("eventType").asText("");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EventValidationException("JSON de evento malformado", e);
        }
    }
}
