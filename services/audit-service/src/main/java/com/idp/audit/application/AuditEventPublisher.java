package com.idp.audit.application;

import com.idp.events.EventEnvelope;

/**
 * Puerto de salida de los eventos que emite el audit-service (alerta de integridad, legalhold.*) hacia
 * {@code audit.events}. Los eventos se validan contra su JSON Schema antes de publicarse (sin PII).
 */
public interface AuditEventPublisher {

    void publish(EventEnvelope event);
}
