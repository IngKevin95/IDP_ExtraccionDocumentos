package com.idp.events;

import java.util.UUID;

/** Fila pendiente del outbox. {@code payload} es el JSON plano del evento ya validado. */
public record OutboxRecord(UUID id, String partitionKey, String eventType, UUID tenantId, String payload,
                           int attempts) {
}
