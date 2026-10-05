package com.idp.audit.infrastructure;

import com.idp.audit.application.AuditEventPublisher;
import com.idp.events.EventEnvelope;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica a {@code auditoria.eventos} (clave tenantId) tras validar el evento contra su JSON Schema. Envio
 * sincrono con confirmacion: si Kafka no confirma, la operacion de negocio falla y no se pierde la alerta.
 */
@Component
public class KafkaAuditEventPublisher implements AuditEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);

    private final ObjectProvider<KafkaTemplate<String, String>> template;
    private final EventSchemaValidator validator;
    private final EventSerde serde;
    private final String topic;

    public KafkaAuditEventPublisher(ObjectProvider<KafkaTemplate<String, String>> template,
                                    EventSchemaValidator validator, EventSerde serde,
                                    @Value("${idp.audit.alerts-topic:auditoria.eventos}") String topic) {
        this.template = template;
        this.validator = validator;
        this.serde = serde;
        this.topic = topic;
    }

    @Override
    public void publish(EventEnvelope event) {
        validator.validate(event);
        KafkaTemplate<String, String> kafka = template.getIfAvailable();
        if (kafka == null) {
            LOG.warn("Kafka no disponible: evento {} del tenant {} no publicado", event.eventType(),
                    event.tenantId());
            return;
        }
        try {
            kafka.send(topic, event.tenantId().toString(), serde.toJson(event)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicacion interrumpida", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No se pudo publicar " + event.eventType(), e);
        }
    }
}
