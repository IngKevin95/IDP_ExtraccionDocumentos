package com.idp.review.infra;

import com.idp.events.EventOriginGuard;
import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.review.service.ReviewIntakeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume el topico de dominio y procesa solo extraccion.requiere_revision y calidad.muestra_ciega_solicitada (tarea
 * de revision ciega), con consumo idempotente (processed_event) y validacion contra su JSON Schema. Los mensajes fuera de contrato van al DLT por el error handler de Kafka.
 */
@Component
public class ReviewEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewEventListener.class);
    static final String HANDLED = "extraccion.requiere_revision";
    static final String BLIND = "calidad.muestra_ciega_solicitada";

    private final IdempotentEventConsumer consumer;
    private final ReviewIntakeHandler handler;
    private final EventSerde serde;
    private final EventOriginGuard guard;

    public ReviewEventListener(IdempotentEventConsumer consumer, ReviewIntakeHandler handler, EventSerde serde,
                               EventOriginGuard guard) {
        this.consumer = consumer;
        this.handler = handler;
        this.serde = serde;
        this.guard = guard;
    }

    @KafkaListener(topics = "${idp.review.topics:#{T(com.idp.events.EventTopology).defaults().topicsFor('extraccion.requiere_revision', 'calidad.muestra_ciega_solicitada')}}",
            groupId = "review-service")
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        String type = eventType(json);
        if ((!HANDLED.equals(type) && !BLIND.equals(type)) || !guard.accepts(topic, type)) {
            return;
        }
        java.util.function.Consumer<com.idp.events.EventEnvelope> work;
        if (BLIND.equals(type)) {
            work = handler::handleBlind;
        } else {
            work = handler::handle;
        }
        if (consumer.consume(json, work) == IdempotentEventConsumer.Result.DUPLICATE) {
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
