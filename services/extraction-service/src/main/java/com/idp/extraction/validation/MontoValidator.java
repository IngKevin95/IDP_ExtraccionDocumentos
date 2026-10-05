package com.idp.extraction.validation;

import java.math.BigDecimal;
import java.util.Optional;

/** RN-02: el monto en numeros debe coincidir exactamente con el monto en letras (campo {@code monto_letras}). */
public final class MontoValidator implements FieldValidator {

    public static final String LETRAS_FIELD = "monto_letras";

    @Override
    public String id() {
        return "monto_numeros_letras";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        if (value == null || value.isBlank()) {
            return ValidationResult.fail("MONTO_NUMEROS_VACIO", "Monto en numeros ausente");
        }
        String letras = ctx.doc(LETRAS_FIELD);
        if (letras == null || letras.isBlank()) {
            return ValidationResult.fail("MONTO_LETRAS_AUSENTE", "Monto en letras ausente");
        }
        Optional<BigDecimal> numeric = AmountParser.parse(value);
        if (numeric.isEmpty()) {
            return ValidationResult.fail("MONTO_NUMEROS_ILEGIBLE", "Monto en numeros no interpretable");
        }
        Optional<Long> words = SpanishNumberWords.parse(letras);
        if (words.isEmpty()) {
            return ValidationResult.fail("MONTO_LETRAS_ILEGIBLE", "Monto en letras no interpretable");
        }
        if (numeric.get().compareTo(BigDecimal.valueOf(words.get())) != 0) {
            return ValidationResult.fail("MONTO_DISCREPANCIA", "Numeros y letras no coinciden");
        }
        return ValidationResult.pass();
    }
}
