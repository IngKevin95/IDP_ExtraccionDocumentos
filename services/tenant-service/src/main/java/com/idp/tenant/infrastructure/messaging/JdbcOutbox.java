package com.idp.tenant.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import com.idp.tenant.infrastructure.persistence.JsonSupport;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox transaccional: se inscribe en la transaccion en curso. El evento se serializa plano
 * (sobre + campos propios) segun los JSON Schema de contracts/events; idempotente por eventId.
 */
@Component
public class JdbcOutbox implements OutboxPublisher {
    public record Pending(UUID id, String partitionKey, String payload) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcOutbox(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String partitionKey, EventEnvelope event) {
        ObjectNode flat = JsonSupport.MAPPER.createObjectNode();
        flat.put("eventId", event.eventId().toString());
        flat.put("eventType", event.eventType());
        flat.put("schemaVersion", event.schemaVersion());
        flat.put("occurredAt", event.occurredAt().toString());
        flat.put("tenantId", event.tenantId().toString());
        flat.put("correlationId", event.correlationId().toString());
        if (event.payload() != null) {
            Iterator<Map.Entry<String, JsonNode>> it = event.payload().fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flat.set(e.getKey(), e.getValue());
            }
        }
        try {
            jdbc.sql("INSERT INTO outbox(id, aggregate_type, aggregate_id, partition_key, type, payload, created_at) "
                            + "VALUES (:id, :at, :ai, :pk, :ty, CAST(:p AS JSONB), :c)")
                    .param("id", event.eventId())
                    .param("at", event.eventType().substring(0, event.eventType().indexOf('.')))
                    .param("ai", event.tenantId().toString()).param("pk", partitionKey)
                    .param("ty", event.eventType()).param("p", flat.toString())
                    .param("c", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)).update();
        } catch (DuplicateKeyException ignored) {
            // idempotencia por eventId
        }
    }

    public List<Pending> findPending(int limit) {
        return jdbc.sql("SELECT id, partition_key, CAST(payload AS VARCHAR) AS payload_text FROM outbox "
                        + "WHERE published_at IS NULL ORDER BY seq LIMIT :l")
                .param("l", limit)
                .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("partition_key"),
                        rs.getString("payload_text")))
                .list();
    }

    public void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox SET published_at = :p WHERE id = :id")
                .param("p", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)).param("id", id).update();
    }
}
