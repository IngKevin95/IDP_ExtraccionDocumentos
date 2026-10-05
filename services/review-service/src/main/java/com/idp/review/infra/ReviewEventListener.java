package com.idp.review.infra;

import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.review.service.ReviewIntakeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume el topico de dominio y procesa solo extraccion.requiere_revision, con consumo idempotente (processed_event)
 * y validacion contra su JSON Schema. Los mensajes fuera de contrato van al DLT por el error handler de Kafka.
 */
@Component
public class ReviewEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewEventListener.class);
    static final String HANDLED = "extraccion.requiere_revision";

    private final IdempotentEventConsumer consumer;
    private final ReviewIntakeHandler handler;
    private final EventSerde serde;

    public ReviewEventListener(IdempotentEventConsumer consumer, ReviewIntakeHandler handler, EventSerde serde) {
        this.consumer = consumer;
        this.handler = handler;
        this.serde = serde;
    }

    @KafkaListener(topics = "${idp.topic:dominio.documentos}", groupId = "review-service")
    public void onMessage(String json) {
        if (!HANDLED.equals(eventType(json))) {
            return;
        }
        if (consumer.consume(json, handler::handle) == IdempotentEventConsumer.Result.DUPLICATE) {
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
