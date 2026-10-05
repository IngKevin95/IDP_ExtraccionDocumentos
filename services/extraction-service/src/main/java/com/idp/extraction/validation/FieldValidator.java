package com.idp.extraction.validation;

/** Validador determinista y sin estado. {@code id()} es el identificador usado en la tipologia YAML. */
public interface FieldValidator {

    /** Longitud maxima de cadena aceptada antes de aplicar regex (SEC-023, anti ReDoS). */
    int MAX_INPUT = 64;

    String id();

    ValidationResult validate(String value, ValidationContext ctx);

    /** Rechazo O(1) de cadenas anomalas antes de cualquier regex. */
    static boolean tooLong(String value) {
        return value != null && value.length() > MAX_INPUT;
    }
}
