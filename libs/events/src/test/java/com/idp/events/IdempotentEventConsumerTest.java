package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.idp.tenant.context.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotentEventConsumerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EventSerde serde = new EventSerde();
    private final IdempotentEventConsumer consumer = new IdempotentEventConsumer(jdbc,
        new TransactionTemplate(mock(PlatformTransactionManager.class)), new EventSchemaValidator(serde), serde);

    @Test
    void ac03_eventoYaProcesadoSeIgnoraSilenciosamente() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        String json = serde.toJson(e);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        List<UUID> handled = new ArrayList<>();

        assertEquals(IdempotentEventConsumer.Result.PROCESSED, consumer.consume(json, ev -> handled.add(ev.eventId())));
        assertEquals(IdempotentEventConsumer.Result.DUPLICATE, consumer.consume(json, ev -> handled.add(ev.eventId())));

        assertEquals(List.of(e.eventId()), handled);
    }

    @Test
    void ac08_mensajeFueraDeContratoEsFatalYNoLlegaAlHandler() {
        List<UUID> handled = new ArrayList<>();
        assertThrows(EventValidationException.class, () -> consumer.consume("{\"x\":1}", ev -> handled.add(ev.eventId())));
        assertThrows(EventValidationException.class, () -> consumer.consume("no-json", ev -> handled.add(ev.eventId())));
        assertEquals(0, handled.size());
    }

    @Test
    void fijaElTenantDelEventoDuranteElHandlerYLoLimpiaDespues() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        List<String> seen = new ArrayList<>();

        consumer.consume(serde.toJson(e), ev -> seen.add(TenantContextHolder.getTenantId()));

        assertEquals(List.of(e.tenantId().toString()), seen);
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void errorDelHandlerSePropagaParaPermitirReintento() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> consumer.consume(serde.toJson(e), ev -> {
            throw new IllegalStateException("db caida");
        }));
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void insercionConcurrenteDuplicadaSeTrataComoYaProcesadoSinEscalar() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DuplicateKeyException("uk processed_event"));
        List<UUID> handled = new ArrayList<>();

        assertEquals(IdempotentEventConsumer.Result.DUPLICATE,
            consumer.consume(serde.toJson(e), ev -> handled.add(ev.eventId())));

        assertEquals(0, handled.size());
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void duplicateKeyDelHandlerNoSeConfundeConDuplicado() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThrows(DuplicateKeyException.class, () -> consumer.consume(serde.toJson(e), ev -> {
            throw new DuplicateKeyException("negocio");
        }));
    }
}
