package com.idp.events;

import com.idp.tenant.context.TenantContextHolder;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumidor idempotente: valida el mensaje contra su schema, registra el eventId en
 * {@code processed_event} y ejecuta el handler en la misma transaccion. Un reintento de entrega
 * de un evento ya procesado se ignora (eventos-kafka AC-03). Si el handler falla, el registro de
 * idempotencia se revierte y el evento puede reintentarse.
 * El tenant del evento se fija en el contexto durante la transaccion para que un DataSource
 * enrutado use el silo correcto.
 */
public final class IdempotentEventConsumer {

    public enum Result { PROCESSED, DUPLICATE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventSchemaValidator validator;
    private final EventSerde serde;

    public IdempotentEventConsumer(JdbcTemplate jdbc, TransactionTemplate tx, EventSchemaValidator validator,
                                   EventSerde serde) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.validator = validator;
        this.serde = serde;
    }

    /**
     * Procesa un mensaje plano JSON.
     *
     * @throws EventValidationException mensaje malformado o fuera de contrato (error fatal)
     */
    public Result consume(String json, Consumer<EventEnvelope> handler) {
        EventEnvelope event = serde.fromJson(json);
        validator.validate(event);
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(event.tenantId().toString());
        try {
            return tx.execute(status -> {
                int inserted = jdbc.update(
                    "insert into processed_event (event_id) values (?) on conflict (event_id) do nothing",
                    event.eventId());
                if (inserted == 0) {
                    return Result.DUPLICATE;
                }
                handler.accept(event);
                return Result.PROCESSED;
            });
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }
}
