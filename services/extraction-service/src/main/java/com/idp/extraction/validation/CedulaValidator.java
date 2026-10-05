package com.idp.extraction.validation;

/** Cedula (CC/CE): solo formato y longitud (6 a 10 digitos), SIN digito de verificacion. */
public final class CedulaValidator implements FieldValidator {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 10;

    @Override
    public String id() {
        return "cedula_formato";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        if (value == null || value.isBlank()) {
            return ValidationResult.fail("CEDULA_VACIA", "Cedula ausente");
        }
        if (FieldValidator.tooLong(value)) {
            return ValidationResult.fail("CEDULA_LONGITUD_EXCEDIDA", "Entrada excede el limite permitido");
        }
        if (value.indexOf('-') >= 0) {
            return ValidationResult.fail("CEDULA_CON_DV", "La cedula no lleva digito de verificacion");
        }
        String digits = NitValidator.digitsOnly(value.trim());
        if (digits == null) {
            return ValidationResult.fail("CEDULA_CARACTER_INVALIDO", "Caracteres no numericos");
        }
        if (digits.length() < MIN_LENGTH || digits.length() > MAX_LENGTH) {
            return ValidationResult.fail("CEDULA_LONGITUD", "Longitud fuera de 6 a 10 digitos");
        }
        return ValidationResult.pass();
    }
}
