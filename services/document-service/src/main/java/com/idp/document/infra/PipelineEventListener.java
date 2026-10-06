package com.idp.document.infra;

import com.idp.document.service.PipelineEventHandler;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume los topicos de extraccion y revision y procesa solo los eventos del pipeline que el document-service
 * orquesta (validando que llegaron por el topico de su productor, SEC-052),
 * con consumo idempotente (processed_event) y validacion contra JSON Schema.
 */
@Component
public class PipelineEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineEventListener.class);
    static final Set<String> HANDLED = Set.of("extraccion.completada", "extraccion.requiere_revision",
            "revision.completada");

    private final IdempotentEventConsumer consumer;
    private final PipelineEventHandler handler;
    private final EventSerde serde;
    private final EventOriginGuard guard;

    public PipelineEventListener(IdempotentEventConsumer consumer, PipelineEventHandler handler, EventSerde serde,
                                 EventOriginGuard guard) {
        this.consumer = consumer;
        this.handler = handler;
        this.serde = serde;
        this.guard = guard;
    }

    @KafkaListener(topics = "${idp.document.topics:#{T(com.idp.events.EventTopology).defaults().topicsFor('extraccion.completada', 'extraccion.requiere_revision', 'revision.completada')}}",
            groupId = "document-service")
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        String type = eventType(json);
        if (!HANDLED.contains(type) || !guard.accepts(topic, type)) {
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
            throw new com.idp.events.EventValidationException("JSON de evento malformado", e);
        }
    }
}
