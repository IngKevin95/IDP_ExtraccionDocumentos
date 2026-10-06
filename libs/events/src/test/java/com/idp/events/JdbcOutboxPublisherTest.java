package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcOutboxPublisherTest {

    private final OutboxRepository repo = mock(OutboxRepository.class);
    private final EventSerde serde = new EventSerde();
    private final JdbcOutboxPublisher publisher =
        new JdbcOutboxPublisher(repo, new EventSchemaValidator(serde), serde);

    @AfterEach
    void endTx() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void ac01_insertaEnOutboxDentroDeLaTransaccionDeNegocio() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());

        publisher.publish("user-7", e);

        verify(repo).insert(eq(e.eventId()), eq("user-7"), eq("acceso.revocado"), eq(e.tenantId()),
            org.mockito.ArgumentMatchers.contains("\"subjectId\":\"user-7\""));
    }

    @Test
    void ac01_sinTransaccionActivaSeRechaza() {
        assertThrows(IllegalStateException.class,
            () -> publisher.publish("k", TestEvents.accesoRevocado(UUID.randomUUID())));
        verify(repo, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void ac06_eventoInvalidoNoSePersiste() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope malo = new EventEnvelope(e.eventId(), e.eventType(), 1, e.occurredAt(), e.tenantId(),
            e.correlationId(), TestEvents.MAPPER.createObjectNode());
        assertThrows(EventValidationException.class, () -> publisher.publish("k", malo));
        verify(repo, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void sec052_productorNoPuedeEncolarEventTypeAjeno() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        JdbcOutboxPublisher quality = new JdbcOutboxPublisher(repo, new EventSchemaValidator(serde), serde,
            EventTopology.defaults(), "quality-service");
        assertThrows(IllegalStateException.class,
            () -> quality.publish("k", TestEvents.accesoRevocado(UUID.randomUUID())));
        verify(repo, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void particionRequerida() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalArgumentException.class,
            () -> publisher.publish(" ", TestEvents.accesoRevocado(UUID.randomUUID())));
    }
}
