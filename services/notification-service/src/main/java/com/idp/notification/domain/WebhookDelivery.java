package com.idp.notification.domain;

import java.time.Instant;
import java.util.UUID;

/** Entrega de un evento a una suscripcion (historial). El payload es claim-check: ids y estado, sin PII. */
public record WebhookDelivery(
        UUID id,
        UUID tenantId,
        UUID webhookId,
        UUID documentId,
        UUID sourceEventId,
        UUID correlationId,
        String eventType,
        String payload,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant deliveredAt,
        Integer lastHttpStatus,
        String errorCode,
        String errorMsg,
        Instant createdAt) {
}
