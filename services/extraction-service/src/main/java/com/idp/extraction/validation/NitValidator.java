package com.idp.extraction.validation;

/**
 * NIT colombiano con digito de verificacion (modulo 11, pesos DIAN). Formato aceptado:
 * {@code 900123456-8}, {@code 900.123.456-8} o 10 digitos corridos (el ultimo es el DV).
 */
public final class NitValidator implements FieldValidator {

    private static final int[] WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};
    private static final int MIN_BASE = 5;

    @Override
    public String id() {
        return "nit_modulo_11";
    }

    /** Digito de verificacion esperado para una base numerica (1 a 15 digitos). */
    public static int checkDigit(String base) {
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int digit = base.charAt(base.length() - 1 - i) - '0';
            sum += digit * WEIGHTS[i];
        }
        int residue = sum % 11;
        return residue > 1 ? 11 - residue : residue;
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        if (value == null || value.isBlank()) {
            return ValidationResult.fail("NIT_VACIO", "NIT ausente");
        }
        if (FieldValidator.tooLong(value)) {
            return ValidationResult.fail("NIT_LONGITUD_EXCEDIDA", "Entrada excede el limite permitido");
        }
        String v = value.trim();
        int dash = v.lastIndexOf('-');
        String basePart;
        String dvPart;
        if (dash >= 0) {
            basePart = v.substring(0, dash);
            dvPart = v.substring(dash + 1).trim();
        } else {
            String digits = digitsOnly(v);
            if (digits == null || digits.length() != 10) {
                return ValidationResult.fail("NIT_FORMATO", "NIT sin guion debe tener 9 digitos base y el DV");
            }
            basePart = digits.substring(0, 9);
            dvPart = digits.substring(9);
        }
        String base = digitsOnly(basePart);
        if (base == null || base.length() < MIN_BASE || base.length() > WEIGHTS.length) {
            return ValidationResult.fail("NIT_FORMATO", "Base del NIT invalida");
        }
        if (dvPart.length() != 1 || dvPart.charAt(0) < '0' || dvPart.charAt(0) > '9') {
            return ValidationResult.fail("NIT_DV_FORMATO", "El digito de verificacion debe ser un digito");
        }
        int expected = checkDigit(base);
        int actual = dvPart.charAt(0) - '0';
        if (expected != actual) {
            return ValidationResult.fail("NIT_DV_INCORRECTO", "Digito de verificacion no coincide");
        }
        return ValidationResult.pass();
    }

    /** Solo digitos, tolerando '.', ',' y espacios como separadores; null si hay otro caracter. */
    static String digitsOnly(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c != '.' && c != ',' && c != ' ') {
                return null;
            }
        }
        return sb.toString();
    }
}
