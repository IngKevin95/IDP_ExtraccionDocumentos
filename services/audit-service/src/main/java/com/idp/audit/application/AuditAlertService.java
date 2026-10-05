package com.idp.audit.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alerta CRITICA de integridad (SEC-039): log, metrica y evento {@code auditoria.alerta_integridad}.
 * Deduplica por (tenant, secuencia, tipo) para no inundar durante el reintento bloqueante.
 */
@Service
public class AuditAlertService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditAlertService.class);
    private static final int MAX_DEDUPE = 10_000;

    private final AuditEventPublisher publisher;
    private final AuditMetrics metrics;
    private final Clock clock;
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();

    public AuditAlertService(AuditEventPublisher publisher, AuditMetrics metrics, Clock clock) {
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void integrityAlert(UUID tenantId, long sequenceId, String errorType, String details) {
        if (alerted.size() >= MAX_DEDUPE) {
            alerted.clear();
        }
        if (!alerted.add(tenantId + "|" + sequenceId + "|" + errorType)) {
            return;
        }
        LOG.error("CRITICAL integridad de auditoria: tenant={} secuencia={} tipo={} detalle={}", tenantId,
                sequenceId, errorType, details);
        metrics.count("audit.integrity.alerts");
        ObjectNode payload = CanonicalJson.mapper().createObjectNode();
        payload.put("sequenceId", sequenceId);
        payload.put("errorType", errorType);
        payload.put("details", details);
        try {
            publisher.publish(new EventEnvelope(UUID.randomUUID(), "auditoria.alerta_integridad", 1,
                    clock.instant(), tenantId, UUID.randomUUID(), payload));
        } catch (RuntimeException e) {
            // El log CRITICAL ya quedo; fallar aqui ocultaria la causa original de la alerta.
            LOG.error("No se pudo publicar la alerta de integridad del tenant {}: {}", tenantId,
                    e.getClass().getSimpleName());
            alerted.remove(tenantId + "|" + sequenceId + "|" + errorType);
        }
    }
}
