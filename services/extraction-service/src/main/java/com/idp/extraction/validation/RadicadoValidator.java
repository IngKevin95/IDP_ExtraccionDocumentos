package com.idp.extraction.validation;

/**
 * Radicado judicial de 23 digitos: DDMMM-EE-SS-DDD-AAAA-NNNNN-RR (la posicion 13-16 es el anio).
 * Acepta separadores (espacios, guiones, puntos) pero exige exactamente 23 digitos.
 */
public final class RadicadoValidator implements FieldValidator {

    public static final int LENGTH = 23;
    private static final int YEAR_START = 12;
    private static final int MIN_YEAR = 1900;

    @Override
    public String id() {
        return "radicado_23_digitos";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        if (value == null || value.isBlank()) {
            return ValidationResult.fail("RADICADO_VACIO", "Radicado ausente");
        }
        if (FieldValidator.tooLong(value)) {
            return ValidationResult.fail("RADICADO_LONGITUD_EXCEDIDA", "Entrada excede el limite permitido");
        }
        StringBuilder digits = new StringBuilder(LENGTH);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != ' ' && c != '-' && c != '.') {
                return ValidationResult.fail("RADICADO_CARACTER_INVALIDO", "Caracteres no numericos");
            }
        }
        if (digits.length() != LENGTH) {
            return ValidationResult.fail("RADICADO_LONGITUD", "Se esperaban 23 digitos y hay " + digits.length());
        }
        int year = Integer.parseInt(digits.substring(YEAR_START, YEAR_START + 4));
        int maxYear = ctx.today().getYear();
        if (year < MIN_YEAR || year > maxYear) {
            return ValidationResult.fail("RADICADO_ANIO", "Anio fuera de rango: " + year);
        }
        return ValidationResult.pass();
    }
}
