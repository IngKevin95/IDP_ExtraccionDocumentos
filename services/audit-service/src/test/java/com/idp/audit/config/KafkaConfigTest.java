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
        ConsumerRecord<String, String> record = new ConsumerRecord<>("auditoria.eventos", 3, 41L, "t", "{}");

        RuntimeException retry = assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new org.springframework.dao.DataAccessResourceFailureException("db caida"),
                List.of((ConsumerRecord) record), consumer, container));

        assertEquals("RecordInRetryException", retry.getClass().getSimpleName());
        verify(consumer).seek(eq(new TopicPartition("auditoria.eventos", 3)), eq(41L));
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
