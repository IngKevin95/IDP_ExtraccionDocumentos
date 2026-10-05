package com.idp.extraction.validation;

import java.time.LocalDate;
import java.util.Map;

/**
 * Contexto de un validador: valores escalares del documento, fila de tabla en curso (vacia para
 * campos escalares) y fecha de recepcion (hoy) en la zona del banco.
 */
public record ValidationContext(Map<String, String> document, Map<String, String> row, LocalDate today) {

    public ValidationContext {
        document = document == null ? Map.of() : Map.copyOf(document);
        row = row == null ? Map.of() : Map.copyOf(row);
    }

    public static ValidationContext of(Map<String, String> document, LocalDate today) {
        return new ValidationContext(document, Map.of(), today);
    }

    public String doc(String name) {
        return document.get(name);
    }

    /** Valor de la fila; si no existe, del documento. */
    public String rowOrDoc(String name) {
        String v = row.get(name);
        return v != null ? v : document.get(name);
    }
}
