package com.idp.extraction.validation;

import java.util.Set;

/** La tipologia debe ser una de las cuatro soportadas: EC, EJ, DC, DJ. */
public final class TipologiaValidaValidator implements FieldValidator {

    private static final Set<String> CODES = Set.of("EC", "EJ", "DC", "DJ");

    @Override
    public String id() {
        return "tipologia_valida";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        return value != null && CODES.contains(value.trim())
            ? ValidationResult.pass()
            : ValidationResult.fail("TIPOLOGIA_INVALIDA", "Tipologia fuera de EC, EJ, DC, DJ");
    }
}
