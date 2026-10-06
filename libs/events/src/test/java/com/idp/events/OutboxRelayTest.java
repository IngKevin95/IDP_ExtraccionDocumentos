package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private final OutboxRepository repo = mock(OutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TenantOutboxAccess access = new TenantOutboxAccess() {
        @Override
        public <T> T inTransaction(String tenantId, Function<OutboxRepository, T> work) {
            if ("roto".equals(tenantId)) {
                throw new IllegalStateException("silo caido");
            }
            return work.apply(repo);
        }
    };

    private OutboxRecord row(UUID tenant, String key) {
        return new OutboxRecord(UUID.randomUUID(), key, "acceso.revocado", tenant, "{\"a\":1}", 0);
    }

    @Test
    void ac02_publicaPendientesYLosMarcaComoProcesados() {
        UUID tenant = UUID.randomUUID();
        OutboxRecord r1 = row(tenant, "k1");
        OutboxRecord r2 = row(tenant, "k2");
        when(repo.lockPending(100)).thenReturn(List.of(r1, r2));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        OutboxRelay relay = new OutboxRelay(access, () -> List.of("t1"), kafka);

        assertEquals(2, relay.relayTenant("t1"));

        verify(repo).markPublished(r1.id());
        verify(repo).markPublished(r2.id());
        verify(repo, never()).markFailed(any(), anyString());
    }

    @Test
    void ac04_mensajeLlevaKeyDeParticionYTenantEnCabeceras() {
        UUID tenant = UUID.randomUUID();
        OutboxRecord r = row(tenant, "agg-1");
        when(repo.lockPending(100)).thenReturn(List.of(r));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        new OutboxRelay(access, () -> List.of("t1"), kafka).relayTenant("t1");

        ArgumentCaptor<ProducerRecord<String, String>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(cap.capture());
        ProducerRecord<String, String> sent = cap.getValue();
        assertEquals("acceso.revocado", sent.topic());
        assertEquals("agg-1", sent.key());
        assertEquals("{\"a\":1}", sent.value());
        assertEquals(tenant.toString(),
            new String(sent.headers().lastHeader("tenantId").value(), StandardCharsets.UTF_8));
    }

    @Test
    void sec052_relayDeProductorPublicaCadaEventTypeEnSuTopico() {
        UUID tenant = UUID.randomUUID();
        OutboxRecord a = new OutboxRecord(UUID.randomUUID(), "d1", "extraccion.completada", tenant, "{}", 0);
        OutboxRecord b = new OutboxRecord(UUID.randomUUID(), "d1", "ia.ejecucion_registrada", tenant, "{}", 0);
        OutboxRecord c = new OutboxRecord(UUID.randomUUID(), "t", "consumo.registrado", tenant, "{}", 0);
        when(repo.lockPending(100)).thenReturn(List.of(a, b, c));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        int n = new OutboxRelay(access, () -> List.of("t1"), kafka, EventTopology.defaults(), "extraction-service",
            100, java.time.Duration.ofSeconds(1)).relayTenant("t1");

        assertEquals(3, n);
        ArgumentCaptor<ProducerRecord<String, String>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(3)).send(cap.capture());
        assertEquals(List.of("extraccion.eventos", "extraccion.eventos", "auditoria.control"),
            cap.getAllValues().stream().map(ProducerRecord::topic).toList());
    }

    @Test
    void sec052_eventTypeAjenoAlProductorFallaYNoSePublica() {
        UUID tenant = UUID.randomUUID();
        OutboxRecord foreign = new OutboxRecord(UUID.randomUUID(), "d1", "revision.completada", tenant, "{}", 0);
        when(repo.lockPending(100)).thenReturn(List.of(foreign));

        int n = new OutboxRelay(access, () -> List.of("t1"), kafka, EventTopology.defaults(), "quality-service",
            100, java.time.Duration.ofSeconds(1)).relayTenant("t1");

        assertEquals(0, n);
        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(repo).markFailed(foreign.id(), "IllegalStateException");
    }

    @Test
    void fallaDePublicacionDetieneElLoteYRegistraElIntento() {
        UUID tenant = UUID.randomUUID();
        OutboxRecord r1 = row(tenant, "k1");
        OutboxRecord r2 = row(tenant, "k2");
        when(repo.lockPending(100)).thenReturn(List.of(r1, r2));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker caido")));

        assertEquals(0, new OutboxRelay(access, () -> List.of("t1"), kafka).relayTenant("t1"));

        verify(repo).markFailed(r1.id(), "ExecutionException");
        verify(repo, never()).markPublished(any());
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void relayAllContinuaConLosDemasTenantsSiUnoFalla() {
        when(repo.lockPending(100)).thenReturn(List.of(row(UUID.randomUUID(), "k")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        int total = new OutboxRelay(access, () -> List.of("roto", "t2"), kafka).relayAll();

        assertEquals(1, total);
    }
}
