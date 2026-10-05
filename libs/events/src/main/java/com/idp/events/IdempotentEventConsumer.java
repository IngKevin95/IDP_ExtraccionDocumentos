package com.idp.events;

import com.idp.tenant.context.TenantContextHolder;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
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
                // Portable (PostgreSQL y H2): sin ON CONFLICT.
                int inserted;
                try {
                    inserted = jdbc.update("insert into processed_event (tenant_id, event_id) "
                        + "select cast(? as uuid), cast(? as uuid) where not exists "
                        + "(select 1 from processed_event p where p.tenant_id = cast(? as uuid) "
                        + "and p.event_id = cast(? as uuid))",
                        event.tenantId(), event.eventId(), event.tenantId(), event.eventId());
                } catch (DuplicateKeyException race) {
                    // Insercion concurrente identica: la otra transaccion gano. Revierte esta (en PostgreSQL
                    // queda abortada) y se trata como ya procesado, sin escalar al error handler.
                    throw new ConcurrentDuplicate(race);
                }
                if (inserted == 0) {
                    return Result.DUPLICATE;
                }
                handler.accept(event);
                return Result.PROCESSED;
            });
        } catch (ConcurrentDuplicate duplicate) {
            return Result.DUPLICATE;
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    /** Senal interna para forzar rollback de la transaccion ante una carrera de unicidad. */
    private static final class ConcurrentDuplicate extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConcurrentDuplicate(Throwable cause) {
            super(cause);
        }
    }
}
