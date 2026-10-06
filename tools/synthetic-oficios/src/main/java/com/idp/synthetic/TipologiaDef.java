package com.idp.synthetic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Vista minima de una tipologia del contrato YAML: campos, criticidad y validadores. */
public record TipologiaDef(String id, Tipo tipo, Map<String, Campo> campos, Map<String, Campo> columnasDemandados) {

    public record Campo(String nombre, boolean critico, String validador) {
    }

    private static final String RESOURCE = "tipologias/embargos.v1.yaml";

    /** Carga las cuatro tipologias del recurso empaquetado (copia del contrato). */
    public static Map<Tipo, TipologiaDef> cargar() {
        try (InputStream in = TipologiaDef.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Falta el recurso " + RESOURCE);
            }
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
            Map<Tipo, TipologiaDef> out = new LinkedHashMap<>();
            for (JsonNode n : root) {
                Tipo tipo = Tipo.parse(n.get("code").asText());
                Map<String, Campo> columnas = new LinkedHashMap<>();
                for (JsonNode t : n.get("tables")) {
                    if ("demandados".equals(t.get("name").asText())) {
                        columnas = campos(t.get("fields"));
                    }
                }
                out.put(tipo, new TipologiaDef(n.get("id").asText(), tipo, campos(n.get("fields")), columnas));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Campo> campos(JsonNode fields) {
        Map<String, Campo> m = new LinkedHashMap<>();
        for (JsonNode f : fields) {
            String nombre = f.get("name").asText();
            m.put(nombre, new Campo(nombre, f.path("critico").asBoolean(false),
                f.hasNonNull("validator") ? f.get("validator").asText() : null));
        }
        return m;
    }
}
