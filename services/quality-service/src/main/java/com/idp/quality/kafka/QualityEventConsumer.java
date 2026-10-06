package com.idp.quality.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.quality.metrics.MetricsIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume los topicos de documentos, extraccion y revision y procesa solo extraccion.aprobada, revision.completada y extraccion.completada, con
 * validacion contra JSON Schema (sin PII) e idempotencia por eventId. Mensajes fuera de contrato van a DLT.
 */
@Component
@ConditionalOnProperty(name = "quality.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class QualityEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(QualityEventConsumer.class);

    private final IdempotentEventConsumer consumer;
    private final MetricsIngestService ingest;
    private final EventSerde serde;
    private final EventOriginGuard guard;

    public QualityEventConsumer(IdempotentEventConsumer consumer, MetricsIngestService ingest, EventSerde serde,
                                EventOriginGuard guard) {
        this.consumer = consumer;
        this.ingest = ingest;
        this.serde = serde;
        this.guard = guard;
    }

    @KafkaListener(topics = "${quality.kafka.topic:#{T(com.idp.events.EventTopology).defaults().topicsFor('extraccion.aprobada', 'revision.completada', 'extraccion.completada')}}",
            groupId = "quality-service")
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        String type = eventType(json);
        if (!MetricsIngestService.HANDLED.contains(type) || !guard.accepts(topic, type)) {
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
