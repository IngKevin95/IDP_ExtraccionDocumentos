package com.idp.tenant.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import com.idp.tenant.infrastructure.persistence.JsonSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Construye los eventos de tenant-service (claim-check: solo identificadores) y los inscribe en el outbox. */
@Component
public class TenantEvents {
    private final OutboxPublisher outbox;
    private final Clock clock;

    public TenantEvents(OutboxPublisher outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    private ObjectNode node() {
        return JsonSupport.MAPPER.createObjectNode();
    }

    private void emit(String type, UUID tenantId, ObjectNode payload) {
        emit(type, tenantId, payload, UUID.randomUUID());
    }

    private void emit(String type, UUID tenantId, ObjectNode payload, UUID correlationId) {
        outbox.publish(tenantId.toString(),
                new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(clock), tenantId, correlationId, payload));
    }

    public void aprovisionado(UUID tenantId, UUID planId, UUID correlationId) {
        emit("tenant.aprovisionado", tenantId, node().put("planId", planId.toString()), correlationId);
    }

    public void aprovisionamientoFallido(UUID tenantId, String reasonCode, UUID correlationId) {
        emit("tenant.aprovisionamiento_fallido", tenantId, node().put("reasonCode", reasonCode), correlationId);
    }

    public void bajaIniciada(UUID tenantId) {
        emit("tenant.baja_iniciada", tenantId, node());
    }

    public void legalHoldAplicado(UUID tenantId, UUID holdId, String reasonCode, String appliedBy) {
        emit("legalhold.aplicado", tenantId,
                node().put("holdId", holdId.toString()).put("reasonCode", reasonCode).put("appliedBy", appliedBy));
    }

    public void legalHoldLiberado(UUID tenantId, UUID holdId, String releasedBy) {
        emit("legalhold.liberado", tenantId, node().put("holdId", holdId.toString()).put("releasedBy", releasedBy));
    }

    public void consumoRegistrado(UUID tenantId, String metric, long value) {
        emit("consumo.registrado", tenantId, node().put("metricName", metric).put("value", value));
    }

    public void cuotaUmbralAlcanzado(UUID tenantId, String metric, int percent) {
        emit("cuota.umbral_alcanzado", tenantId, node().put("metricName", metric).put("thresholdPercentage", percent));
    }

    public void accesoRevocado(UUID tenantId, String subjectId) {
        emit("acceso.revocado", tenantId, node().put("subjectId", subjectId));
    }

    public void breakglassOtorgado(UUID tenantId, String subjectId, String approvedBy, Instant expiresAt) {
        emit("breakglass.otorgado", tenantId,
                node().put("subjectId", subjectId).put("approvedBy", approvedBy).put("expiresAt", expiresAt.toString()));
    }

    public void breakglassExpirado(UUID tenantId, String subjectId) {
        emit("breakglass.expirado", tenantId, node().put("subjectId", subjectId));
    }
}
