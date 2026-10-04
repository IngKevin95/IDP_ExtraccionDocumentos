package com.idp.tenant.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Jackson 2 (el mismo que usa libs/events) para columnas JSONB y eventos. */
public final class JsonSupport {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON no serializable", e);
        }
    }

    public static Map<String, Object> readMap(String s) {
        try {
            return s == null || s.isBlank() ? Map.of() : MAPPER.readValue(s, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON invalido en base de control", e);
        }
    }

    public static Map<String, Long> readLongMap(String s) {
        try {
            return s == null || s.isBlank() ? Map.of() : MAPPER.readValue(s, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON invalido en base de control", e);
        }
    }
}
