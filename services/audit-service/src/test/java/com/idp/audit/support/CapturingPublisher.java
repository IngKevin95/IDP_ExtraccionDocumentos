package com.idp.audit.support;

import com.idp.audit.application.AuditEventPublisher;
import com.idp.events.EventEnvelope;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captura los eventos emitidos y los valida contra su JSON Schema (sin PII, SEC-050). */
public final class CapturingPublisher implements AuditEventPublisher {

    private final EventSchemaValidator validator = new EventSchemaValidator(new EventSerde());
    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(EventEnvelope event) {
        validator.validate(event);
        events.add(event);
    }

    public List<EventEnvelope> of(UUID tenant, String type) {
        return events.stream().filter(e -> e.tenantId().equals(tenant) && e.eventType().equals(type)).toList();
    }
}
