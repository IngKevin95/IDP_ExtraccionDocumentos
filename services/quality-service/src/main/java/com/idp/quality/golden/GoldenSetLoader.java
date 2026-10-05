package com.idp.quality.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Carga el golden set desde los JSON de verdad terreno que genera tools/synthetic-oficios. Cada archivo exige
 * {@code "sintetico": true} (RN-07): un archivo sin esa marca se rechaza y nunca se persiste.
 *
 * <p>Formato: {@code {"id":"ec-0001","tipologia":"EC","sintetico":true,"tags":["ruido"],"verdad":{"radicado":"..."}}}
 */
public final class GoldenSetLoader {

    /** Oficio leido de archivo, aun sin identificador persistente. */
    public record Loaded(String externalId, String nombre, String tipologia, List<String> tags,
                         Map<String, String> verdad) {
    }

    private final ObjectMapper mapper;

    public GoldenSetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Loaded> loadDirectory(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<Loaded> out = new ArrayList<>();
        for (Path f : files) {
            out.add(parse(mapper.readTree(f.toFile()), String.valueOf(f.getFileName())));
        }
        return out;
    }

    public Loaded parse(JsonNode n, String origin) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException(origin + ": se esperaba un objeto JSON");
        }
        if (!n.path("sintetico").asBoolean(false)) {
            throw new IllegalArgumentException(origin + ": el oficio no esta marcado como sintetico");
        }
        String id = text(n, "id");
        String tipologia = text(n, "tipologia");
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException(origin + ": id invalido");
        }
        if (tipologia == null || !tipologia.matches("[A-Z]{2,4}")) {
            throw new IllegalArgumentException(origin + ": tipologia invalida");
        }
        JsonNode truth = n.has("verdad") ? n.get("verdad") : n.get("payload_sintetico_json");
        Map<String, String> verdad = toTruth(truth, origin);
        List<String> tags = new ArrayList<>();
        if (n.path("tags").isArray()) {
            n.get("tags").forEach(t -> tags.add(t.asText()));
        }
        String nombre = text(n, "nombre");
        return new Loaded(id, nombre == null ? id : nombre, tipologia, tags, verdad);
    }

    /** Verdad terreno como mapa campo a texto; los valores compuestos se guardan como JSON canonico. */
    public static Map<String, String> toTruth(JsonNode truth, String origin) {
        if (truth == null || !truth.isObject() || truth.isEmpty()) {
            throw new IllegalArgumentException(origin + ": verdad terreno ausente");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : truth.properties()) {
            if (!e.getKey().matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException(origin + ": nombre de campo invalido");
            }
            JsonNode v = e.getValue();
            if (v.isNumber()) {
                out.put(e.getKey(), v.decimalValue().toPlainString());
            } else if (v.isValueNode()) {
                out.put(e.getKey(), v.asText());
            } else {
                out.put(e.getKey(), v.toString());
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }
}
