package com.idp.audit.infrastructure;

import com.idp.audit.application.AuditIngestionService;
import com.idp.events.IdempotentEventConsumer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume todos los topicos de dominio y {@code auditoria.eventos} con consumer group propio. El orden de la
 * cadena lo fija la ingesta en BD (no el offset). Un fallo de persistencia propaga la excepcion al
 * {@code DefaultErrorHandler} de reintento bloqueante: el offset no avanza ni se envia a DLT (AC-06).
 */
@Component
public class AuditDomainEventConsumer {

    private final IdempotentEventConsumer consumer;
    private final AuditIngestionService ingestion;

    public AuditDomainEventConsumer(IdempotentEventConsumer consumer, AuditIngestionService ingestion) {
        this.consumer = consumer;
        this.ingestion = ingestion;
    }

    @KafkaListener(topics = "#{'${idp.audit.topics}'.split(',')}",
            groupId = "${spring.kafka.consumer.group-id:audit-service}")
    public void onMessage(String json) {
        consumer.consume(json, ingestion::ingest);
    }
}
