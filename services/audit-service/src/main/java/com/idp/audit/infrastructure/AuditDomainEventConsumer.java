package com.idp.audit.infrastructure;

import com.idp.audit.application.AuditIngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume todos los topicos de contracts/events/topology.yaml con consumer group propio. Cada evento debe llegar
 * por el topico que la topologia asigna a su eventType (SEC-052); si no, se ignora con alerta SECURITY. Los eventos
 * de {@code audit.events} se tratan como senales, no como estado autoritativo. El orden de la
 * cadena lo fija la ingesta en BD (no el offset). Un fallo de persistencia propaga la excepcion al
 * {@code DefaultErrorHandler} de reintento bloqueante: el offset no avanza ni se envia a DLT (AC-06).
 */
@Component
public class AuditDomainEventConsumer {

    private final IdempotentEventConsumer consumer;
    private final AuditIngestionService ingestion;
    private final EventSerde serde;
    private final EventOriginGuard guard;

    public AuditDomainEventConsumer(IdempotentEventConsumer consumer, AuditIngestionService ingestion,
                                    EventSerde serde, EventOriginGuard guard) {
        this.consumer = consumer;
        this.ingestion = ingestion;
        this.serde = serde;
        this.guard = guard;
    }

    @KafkaListener(topics = "#{'${idp.audit.topics:}'.isEmpty() ? T(com.idp.events.EventTopology).defaults().allTopics() : '${idp.audit.topics:}'.split(',')}",
            groupId = "${spring.kafka.consumer.group-id:audit-service}")
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        String type = eventType(json);
        if (type != null && !guard.accepts(topic, type)) {
            return;
        }
        consumer.consume(json, ingestion::ingest);
    }

    /** eventType del mensaje; {@code null} si es JSON malformado (lo rechaza el consumidor idempotente). */
    private String eventType(String json) {
        try {
            return serde.mapper().readTree(json).path("eventType").asText("");
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
