package com.idp.extraction.typology;

/** Tipos de dato soportados por el esquema de tipologia (tipologia.schema.json). */
public enum FieldType {
    STRING, DECIMAL, DATE;

    public static FieldType parse(String raw) {
        return switch (raw) {
            case "string" -> STRING;
            case "decimal" -> DECIMAL;
            case "date" -> DATE;
            default -> throw new InvalidTypologyException("Tipo de campo no soportado: " + raw);
        };
    }
}
