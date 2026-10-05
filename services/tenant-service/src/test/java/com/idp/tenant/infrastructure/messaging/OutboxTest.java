package com.idp.tenant.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idp.events.EventEnvelope;
import com.idp.tenant.application.TenantEvents;
import com.idp.tenant.infrastructure.persistence.JsonSupport;
import com.idp.tenant.support.ApiTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxTest extends ApiTestSupport {
    @Autowired JdbcOutbox outbox;
    @Autowired TenantEvents events;
    @Autowired TransactionTemplate tx;

    @Test
    void outboxExigeTransaccionYEsIdempotentePorEventId() throws Exception {
        UUID t = createTenant();
        EventEnvelope ev = new EventEnvelope(UUID.randomUUID(), "tenant.baja_iniciada", 1, Instant.now(), t,
                UUID.randomUUID(), JsonSupport.MAPPER.createObjectNode());
        try {
            outbox.publish(t.toString(), ev);
            throw new AssertionError("debio exigir transaccion");
        } catch (org.springframework.transaction.IllegalTransactionStateException expected) {
            // MANDATORY
        }
        tx.executeWithoutResult(s -> {
            outbox.publish(t.toString(), ev);
            outbox.publish(t.toString(), ev);
        });
        assertEquals(1, events(t, "tenant.baja_iniciada").size());
        assertValid("tenant.baja_iniciada", events(t, "tenant.baja_iniciada").get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublicaPendientesLosMarcaYReintentaSiKafkaFalla() throws Exception {
        UUID t = createTenant();
        tx.executeWithoutResult(s -> events.bajaIniciada(t));

        KafkaTemplate<String, String> broken = mock(KafkaTemplate.class);
        when(broken.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker caido")));
        assertEquals(0, new OutboxRelay(outbox, broken, "topic.test").relay());
        assertEquals(1, pending(t));

        KafkaTemplate<String, String> ok = mock(KafkaTemplate.class);
        when(ok.send(eq("topic.test"), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        OutboxRelay relay = new OutboxRelay(outbox, ok, "topic.test");
        while (relay.relay() > 0) {
            // drena el outbox compartido por los demas tests
        }
        assertEquals(0, pending(t));
        verify(ok, atLeastOnce()).send(eq("topic.test"), eq(t.toString()), any(String.class));
    }

    private int pending(UUID tenant) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE aggregate_id = :t AND published_at IS NULL AND type = 'tenant.baja_iniciada'")
                .param("t", tenant.toString()).query(Integer.class).single();
    }
}
