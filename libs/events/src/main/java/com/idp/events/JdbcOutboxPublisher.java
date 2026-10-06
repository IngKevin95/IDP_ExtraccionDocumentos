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
    private final EventTopology topology;
    private final String producerService;

    public JdbcOutboxPublisher(OutboxRepository repository, EventSchemaValidator validator, EventSerde serde) {
        this(repository, validator, serde, null, null);
    }

    /** Publicador de un servicio productor: rechaza al encolar los eventType que el servicio no produce. */
    public JdbcOutboxPublisher(OutboxRepository repository, EventSchemaValidator validator, EventSerde serde,
                               EventTopology topology, String producerService) {
        this.repository = repository;
        this.validator = validator;
        this.serde = serde;
        this.topology = topology;
        this.producerService = producerService;
    }

    @Override
    public void publish(String partitionKey, EventEnvelope event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El outbox exige una transaccion de negocio activa");
        }
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey requerida");
        }
        if (topology != null) {
            topology.allowedTopicFor(producerService, event.eventType());
        }
        validator.validate(event);
        repository.insert(event.eventId(), partitionKey, event.eventType(), event.tenantId(), serde.toJson(event));
    }
}
