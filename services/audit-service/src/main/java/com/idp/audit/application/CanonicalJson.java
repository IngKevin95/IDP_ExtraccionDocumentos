package com.idp.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Serializacion JSON canonica: claves de objeto ordenadas, sin espacios. Es la entrada de hashes y
 * firmas, asi que no depende del orden de claves que devuelva JSONB al releer.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        append(node, sb);
        return sb.toString();
    }

    public static byte[] bytes(JsonNode node) {
        return write(node).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON invalido", e);
        }
    }

    private static void append(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            Collections.sort(names);
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(names.get(i))).append(':');
                append(node.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                append(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isTextual()) {
            sb.append(quote(node.asText()));
        } else {
            sb.append(node.toString());
        }
    }

    private static String quote(String s) {
        try {
            return MAPPER.writeValueAsString(new TextNode(s));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
