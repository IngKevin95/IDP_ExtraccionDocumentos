package com.idp.extraction.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Datos extraidos de un documento: campos escalares y filas de tablas, mutables durante el procesamiento. */
public final class ExtractionData {

    private final Map<String, ExtractedValue> fields = new LinkedHashMap<>();
    private final Map<String, List<Map<String, ExtractedValue>>> tables = new LinkedHashMap<>();

    public Map<String, ExtractedValue> fields() {
        return fields;
    }

    public Map<String, List<Map<String, ExtractedValue>>> tables() {
        return tables;
    }

    public ExtractedValue get(FieldKey key) {
        if (!key.isCell()) {
            return fields.get(key.name());
        }
        List<Map<String, ExtractedValue>> rows = tables.get(key.table());
        if (rows == null || key.row() < 0 || key.row() >= rows.size()) {
            return null;
        }
        return rows.get(key.row()).get(key.name());
    }

    public void put(FieldKey key, ExtractedValue value) {
        if (!key.isCell()) {
            fields.put(key.name(), value);
            return;
        }
        List<Map<String, ExtractedValue>> rows = tables.computeIfAbsent(key.table(), k -> new ArrayList<>());
        while (rows.size() <= key.row()) {
            rows.add(new LinkedHashMap<>());
        }
        rows.get(key.row()).put(key.name(), value);
    }

    /** Valores escalares como texto (para validadores). */
    public Map<String, String> scalarValues() {
        Map<String, String> m = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (v != null && v.present()) {
                m.put(k, v.value());
            }
        });
        return m;
    }

    /** Filas de una tabla como texto plano por columna. */
    public List<Map<String, String>> rowValues(String table) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, ExtractedValue> row : tables.getOrDefault(table, List.of())) {
            Map<String, String> r = new LinkedHashMap<>();
            row.forEach((k, v) -> {
                if (v != null && v.present()) {
                    r.put(k, v.value());
                }
            });
            out.add(r);
        }
        return out;
    }
}
