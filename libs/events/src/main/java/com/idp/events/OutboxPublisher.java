package com.idp.events;

import com.fasterxml.jackson.databind.JsonNode;

public interface OutboxPublisher {
    /**
     * Publishes an event to the outbox table.
     * Implementations must handle idempotency (e.g., using eventId).
     * @param partitionKey The partition key for ordering (e.g. aggregateId).
     * @param event The event envelope.
     */
    void publish(String partitionKey, EventEnvelope event);
}
