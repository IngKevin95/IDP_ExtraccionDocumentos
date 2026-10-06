package com.idp.audit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.idp.audit.application.AuditMetrics;
import com.idp.events.EventValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;

/** AC-06: reintento bloqueante, sin DLT y sin avanzar el offset ante fallos de persistencia. */
class KafkaConfigTest {

    @Test
    void ac06_elBackoffNuncaSeAgotaYEstaAcotado() {
        BackOffExecution exec = KafkaConfig.blockingBackOff().start();
        long max = 0;
        for (int i = 0; i < 10_000; i++) {
            long next = exec.nextBackOff();
            assertNotEquals(BackOffExecution.STOP, next, "el reintento debe ser indefinido (intento " + i + ")");
            max = Math.max(max, next);
        }
        assertEquals(30_000L, max);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac06_falloDePersistenciaHaceSeekAlMismoOffsetSinRecuperarNiEnviarADlt() {
        AuditMetrics metrics = new AuditMetrics(new SimpleMeterRegistry());
        DefaultErrorHandler handler = new KafkaConfig().auditErrorHandler(metrics);
        Consumer consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("audit.control", 3, 41L, "t", "{}");

        RuntimeException retry = assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new org.springframework.dao.DataAccessResourceFailureException("db caida"),
                List.of((ConsumerRecord) record), consumer, container));

        assertEquals("RecordInRetryException", retry.getClass().getSimpleName());
        verify(consumer).seek(eq(new TopicPartition("audit.control", 3)), eq(41L));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sec053_erroresAtribuiblesAlContenidoSeDescartanYLosDeInfraestructuraSeReintentan() {
        List<RuntimeException> contenido = List.of(
                new com.idp.audit.domain.Exceptions.UnknownTenantException("x"),
                new IllegalArgumentException("uuid"),
                new org.springframework.dao.DataIntegrityViolationException("demasiado largo"));
        for (RuntimeException ex : contenido) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            DefaultErrorHandler handler = new KafkaConfig().auditErrorHandler(new AuditMetrics(registry));
            Consumer consumer = mock(Consumer.class);
            ConsumerRecord<String, String> record = new ConsumerRecord<>("audit.control", 0, 9L, "t", "{}");
            handler.handleRemaining(ex, List.of((ConsumerRecord) record), consumer,
                    mock(MessageListenerContainer.class));
            assertEquals(1.0, registry.counter("audit.ingestion.rejected").count(), ex.getClass().getSimpleName());
        }
        List<RuntimeException> infra = List.of(
                new org.springframework.dao.DataAccessResourceFailureException("db caida"),
                new com.idp.audit.domain.Exceptions.ChainIntegrityException(java.util.UUID.randomUUID(), 3, "HASH_MISMATCH", "m"));
        for (RuntimeException ex : infra) {
            DefaultErrorHandler handler = new KafkaConfig().auditErrorHandler(new AuditMetrics(new SimpleMeterRegistry()));
            Consumer consumer = mock(Consumer.class);
            ConsumerRecord<String, String> record = new ConsumerRecord<>("audit.control", 0, 9L, "t", "{}");
            assertThrows(RuntimeException.class, () -> handler.handleRemaining(ex, List.of((ConsumerRecord) record),
                    consumer, mock(MessageListenerContainer.class)));
            verify(consumer).seek(eq(new TopicPartition("audit.control", 0)), eq(9L));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac06_soloElMensajeFueraDeContratoSeDescartaConAlertaCritica() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultErrorHandler handler = new KafkaConfig().auditErrorHandler(new AuditMetrics(registry));
        Consumer consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("dominio.documentos", 0, 7L, "t", "{}");

        handler.handleRemaining(new EventValidationException("fuera de contrato"),
                List.of((ConsumerRecord) record), consumer, container);

        assertEquals(1.0, registry.counter("audit.ingestion.rejected").count());
        verify(consumer, org.mockito.Mockito.never()).seek(any(TopicPartition.class), eq(7L));
    }
}
