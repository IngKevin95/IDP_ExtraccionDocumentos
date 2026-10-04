package com.idp.events;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publica a la tabla outbox dentro de la transaccion de negocio en curso (eventos-kafka AC-01).
 * Valida el evento contra su JSON Schema antes de persistirlo (AC-06, SEC-050).
 */
public final class JdbcOutboxPublisher implements OutboxPublisher {

    private final OutboxRepository repository;
    private final EventSchemaValidator validator;
    private final EventSerde serde;

    public JdbcOutboxPublisher(OutboxRepository repository, EventSchemaValidator validator, EventSerde serde) {
        this.repository = repository;
        this.validator = validator;
        this.serde = serde;
    }

    @Override
    public void publish(String partitionKey, EventEnvelope event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El outbox exige una transaccion de negocio activa");
        }
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey requerida");
        }
        validator.validate(event);
        repository.insert(event.eventId(), partitionKey, event.eventType(), event.tenantId(), serde.toJson(event));
    }
}
