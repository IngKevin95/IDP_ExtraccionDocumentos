package com.idp.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Valida eventos contra los JSON Schema versionados de contracts/events
 * ({@code <eventType>.v<schemaVersion>.schema.json}). Los esquemas se empaquetan en el
 * classpath bajo {@code contracts/events/} durante el build de esta libreria.
 */
public final class EventSchemaValidator {

    private static final String CLASSPATH_DIR = "contracts/events/";

    private final EventSerde serde;
    private final Function<String, InputStream> loader;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ConcurrentHashMap<String, JsonSchema> cache = new ConcurrentHashMap<>();

    public EventSchemaValidator(EventSerde serde) {
        this(serde, name -> EventSchemaValidator.class.getClassLoader().getResourceAsStream(CLASSPATH_DIR + name));
    }

    /** Permite cargar los esquemas desde otro origen (directorio, registro). */
    public EventSchemaValidator(EventSerde serde, Function<String, InputStream> loader) {
        this.serde = serde;
        this.loader = loader;
    }

    public void validate(EventEnvelope event) {
        validateFlat(serde.toFlatNode(event));
    }

    public void validateFlat(JsonNode flat) {
        String type = flat.path("eventType").asText("");
        int version = flat.path("schemaVersion").asInt(0);
        if (type.isEmpty() || version < 1) {
            throw new EventValidationException("Evento sin eventType o schemaVersion");
        }
        JsonSchema schema = schemaFor(type, version);
        Set<ValidationMessage> errors = schema.validate(flat);
        if (!errors.isEmpty()) {
            String detail = errors.stream().map(ValidationMessage::getMessage).sorted()
                .collect(Collectors.joining("; "));
            throw new EventValidationException("Evento " + type + " v" + version + " invalido: " + detail);
        }
    }

    private JsonSchema schemaFor(String type, int version) {
        if (!type.matches("[a-z0-9_.]+")) {
            throw new EventValidationException("eventType invalido");
        }
        String file = type + ".v" + version + ".schema.json";
        JsonSchema cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = loader.apply(file)) {
            if (in == null) {
                throw new EventValidationException("Sin esquema registrado para " + type + " v" + version);
            }
            JsonSchema schema = factory.getSchema(in);
            cache.put(file, schema);
            return schema;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
