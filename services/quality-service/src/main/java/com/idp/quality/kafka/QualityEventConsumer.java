package com.idp.quality.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.quality.metrics.MetricsIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume el topico de dominio y procesa solo extraccion.aprobada, revision.completada y extraccion.completada, con
 * validacion contra JSON Schema (sin PII) e idempotencia por eventId. Mensajes fuera de contrato van a DLT.
 */
@Component
@ConditionalOnProperty(name = "quality.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class QualityEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(QualityEventConsumer.class);

    private final IdempotentEventConsumer consumer;
    private final MetricsIngestService ingest;
    private final EventSerde serde;

    public QualityEventConsumer(IdempotentEventConsumer consumer, MetricsIngestService ingest, EventSerde serde) {
        this.consumer = consumer;
        this.ingest = ingest;
        this.serde = serde;
    }

    @KafkaListener(topics = "${quality.kafka.topic:dominio.documentos}", groupId = "quality-service")
    public void onMessage(String json) {
        if (!MetricsIngestService.HANDLED.contains(eventType(json))) {
            return;
        }
        if (consumer.consume(json, ingest::handle) == IdempotentEventConsumer.Result.DUPLICATE) {
            LOG.debug("Evento duplicado ignorado");
        }
    }

    private String eventType(String json) {
        try {
            return serde.mapper().readTree(json).path("eventType").asText("");
        } catch (JsonProcessingException e) {
            throw new EventValidationException("JSON de evento malformado", e);
        }
    }
}
