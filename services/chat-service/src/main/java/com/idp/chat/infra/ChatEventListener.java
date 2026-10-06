package com.idp.chat.infra;

import com.idp.chat.infra.ChunkRepository.NewChunk;
import com.idp.chat.service.IndexDocumentService;
import com.idp.events.EventEnvelope;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.tenant.context.TenantContextHolder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume document.events y procesa solo extraccion.aprobada (indexacion RAG, AC-01). Valida el topico de origen
 * (SEC-052), el JSON Schema y los UUID; lo que no cumple el contrato va al DLT por el error handler de Kafka. El tenant
 * del evento fija el silo (TenantContextHolder) y el consumo es idempotente (processed_event + estado de indexacion).
 * La lectura del almacen y los embeddings se hacen fuera de la transaccion; esta solo guarda el resultado.
 */
@Component
public class ChatEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ChatEventListener.class);
    static final String HANDLED = "extraccion.aprobada";

    private final IdempotentEventConsumer consumer;
    private final IndexDocumentService indexer;
    private final EventSerde serde;
    private final EventSchemaValidator validator;
    private final EventOriginGuard guard;

    public ChatEventListener(IdempotentEventConsumer consumer, IndexDocumentService indexer, EventSerde serde,
                             EventSchemaValidator validator, EventOriginGuard guard) {
        this.consumer = consumer;
        this.indexer = indexer;
        this.serde = serde;
        this.validator = validator;
        this.guard = guard;
    }

    @KafkaListener(topics = "${idp.chat.topics:#{T(com.idp.events.EventTopology).defaults().topicsFor('extraccion.aprobada')}}",
            groupId = "chat-service")
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        String type = eventType(json);
        if (!HANDLED.equals(type) || !guard.accepts(topic, type)) {
            return;
        }
        EventEnvelope event = serde.fromJson(json);
        validator.validate(event);
        UUID documentId = parseUuid(event.payload().path("documentId").asText(""));
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(event.tenantId().toString());
        try {
            Optional<List<NewChunk>> prepared = indexer.prepare(event.tenantId(), documentId);
            if (prepared.isEmpty()) {
                return;
            }
            if (consumer.consume(json, e -> indexer.persist(documentId, prepared.get()))
                    == IdempotentEventConsumer.Result.DUPLICATE) {
                LOG.debug("Evento duplicado ignorado");
            }
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new EventValidationException("documentId invalido");
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
