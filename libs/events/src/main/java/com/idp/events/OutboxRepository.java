package com.idp.events;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Acceso JDBC a la tabla {@code outbox} del silo del tenant (ver db/events/outbox-schema.sql). */
public class OutboxRepository {

    private static final int MAX_ERROR = 500;

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserta en estado PENDING; ignora duplicados por eventId. Devuelve filas insertadas. */
    public int insert(UUID eventId, String partitionKey, String eventType, UUID tenantId, String payload) {
        // Sentencia portable (PostgreSQL y H2): INSERT ... SELECT ... WHERE NOT EXISTS en lugar de ON CONFLICT.
        return jdbc.update("insert into outbox (id, partition_key, event_type, tenant_id, payload) "
            + "select cast(? as uuid), cast(? as varchar(255)), cast(? as varchar(120)), cast(? as uuid), "
            + "cast(? as text) where not exists (select 1 from outbox o where o.id = cast(? as uuid))",
            eventId, partitionKey, eventType, tenantId, payload, eventId);
    }

    /** Bloquea hasta {@code limit} filas pendientes saltando las bloqueadas por otras instancias. */
    public List<OutboxRecord> lockPending(int limit) {
        return jdbc.query("select id, partition_key, event_type, tenant_id, payload, attempts from outbox "
            + "where status = 'PENDING' order by seq limit ? for update skip locked",
            (rs, i) -> new OutboxRecord(rs.getObject("id", UUID.class), rs.getString("partition_key"),
                rs.getString("event_type"), rs.getObject("tenant_id", UUID.class), rs.getString("payload"),
                rs.getInt("attempts")),
            limit);
    }

    public void markPublished(UUID id) {
        jdbc.update("update outbox set status = 'PUBLISHED', published_at = now() where id = ?", id);
    }

    public void markFailed(UUID id, String error) {
        String msg = error == null ? "" : error.substring(0, Math.min(error.length(), MAX_ERROR));
        jdbc.update("update outbox set attempts = attempts + 1, last_error = ? where id = ?", msg, id);
    }
}
