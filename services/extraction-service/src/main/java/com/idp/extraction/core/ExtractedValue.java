package com.idp.extraction.core;

/** Valor devuelto por el LLM para un campo; {@code value} nulo significa "no encontrado". */
public record ExtractedValue(String value, Double confidence, Evidence evidence) {

    public boolean present() {
        return value != null && !value.isBlank();
    }
}
