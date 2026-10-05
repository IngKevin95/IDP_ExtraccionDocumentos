package com.idp.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Serializacion del sobre al formato plano de contracts/events: los campos del sobre
 * (eventId, eventType, schemaVersion, occurredAt, tenantId, correlationId) conviven al
 * mismo nivel que los campos del payload.
 */
public final class EventSerde {

    private static final Set<String> ENVELOPE_FIELDS =
        Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "tenantId", "correlationId");

    private final ObjectMapper mapper;

    public EventSerde() {
        this(new ObjectMapper());
    }

    public EventSerde(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public ObjectNode toFlatNode(EventEnvelope e) {
        ObjectNode node = mapper.createObjectNode();
        JsonNode payload = e.payload();
        if (payload != null && payload.isObject()) {
            for (Map.Entry<String, JsonNode> f : payload.properties()) {
                node.set(f.getKey(), f.getValue());
            }
        }
        node.put("eventId", e.eventId().toString());
        node.put("eventType", e.eventType());
        node.put("schemaVersion", e.schemaVersion());
        node.put("occurredAt", e.occurredAt().toString());
        node.put("tenantId", e.tenantId().toString());
        node.put("correlationId", e.correlationId().toString());
        return node;
    }

    public String toJson(EventEnvelope e) {
        try {
            return mapper.writeValueAsString(toFlatNode(e));
        } catch (JsonProcessingException ex) {
            throw new EventValidationException("No se pudo serializar el evento", ex);
        }
    }

    public EventEnvelope fromJson(String json) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JsonProcessingException | RuntimeException ex) {
            throw new EventValidationException("JSON de evento malformado", ex);
        }
        return fromFlatNode(node);
    }

    public EventEnvelope fromFlatNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new EventValidationException("El evento debe ser un objeto JSON");
        }
        try {
            ObjectNode payload = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!ENVELOPE_FIELDS.contains(f.getKey())) {
                    payload.set(f.getKey(), f.getValue());
                }
            }
            return new EventEnvelope(
                UUID.fromString(required(node, "eventId")),
                required(node, "eventType"),
                node.path("schemaVersion").asInt(0),
                Instant.parse(required(node, "occurredAt")),
                UUID.fromString(required(node, "tenantId")),
                UUID.fromString(required(node, "correlationId")),
                payload);
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new EventValidationException("Campos de sobre invalidos", ex);
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            throw new EventValidationException("Falta el campo de sobre " + field);
        }
        return v.asText();
    }
}
