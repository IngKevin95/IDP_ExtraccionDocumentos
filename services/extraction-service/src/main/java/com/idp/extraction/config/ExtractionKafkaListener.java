package com.idp.extraction.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer;
import com.idp.extraction.core.ExtractionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Adaptador primario Kafka: consume {@code extraccion.solicitada} de forma idempotente (RNF-105). */
@Component
@ConditionalOnProperty(name = "extraction.runtime.enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionKafkaListener {

    static final String COMMAND_TYPE = "extraccion.solicitada";
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionKafkaListener.class);

    private final IdempotentEventConsumer consumer;
    private final ExtractionProcessor processor;
    private final ObjectMapper mapper;
    private final EventOriginGuard guard;

    public ExtractionKafkaListener(IdempotentEventConsumer consumer, ExtractionProcessor processor,
                                   ObjectMapper idpObjectMapper, EventOriginGuard guard) {
        this.consumer = consumer;
        this.processor = processor;
        this.mapper = idpObjectMapper;
        this.guard = guard;
    }

    @KafkaListener(topics = "${extraction.kafka.command-topic:#{T(com.idp.events.EventTopology).defaults().topicsFor('extraccion.solicitada')}}",
        groupId = "${spring.application.name}")
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        if (!isCommand(json) || !guard.accepts(topic, COMMAND_TYPE)) {
            return;
        }
        IdempotentEventConsumer.Result result = consumer.consume(json, processor::process);
        if (result == IdempotentEventConsumer.Result.DUPLICATE) {
            LOG.info("Comando de extraccion duplicado ignorado");
        }
    }

    private boolean isCommand(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return COMMAND_TYPE.equals(node.path("eventType").asText());
        } catch (java.io.IOException e) {
            throw new EventValidationException("JSON de evento malformado", e);
        }
    }
}
