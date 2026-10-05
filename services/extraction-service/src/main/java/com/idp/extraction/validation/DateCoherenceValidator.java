package com.idp.extraction.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** La fecha del oficio (ISO-8601) no puede ser posterior al dia de recepcion (hoy). */
public final class DateCoherenceValidator implements FieldValidator {

    private static final LocalDate MIN_DATE = LocalDate.of(1990, 1, 1);

    @Override
    public String id() {
        return "fechas_coherentes";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        if (value == null || value.isBlank()) {
            return ValidationResult.fail("FECHA_VACIA", "Fecha ausente");
        }
        if (FieldValidator.tooLong(value)) {
            return ValidationResult.fail("FECHA_LONGITUD_EXCEDIDA", "Entrada excede el limite permitido");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return ValidationResult.fail("FECHA_FORMATO", "La fecha debe ser ISO-8601 (AAAA-MM-DD)");
        }
        if (date.isAfter(ctx.today())) {
            return ValidationResult.fail("FECHA_FUTURA", "La fecha del oficio es posterior a la recepcion");
        }
        if (date.isBefore(MIN_DATE)) {
            return ValidationResult.fail("FECHA_ANTIGUA", "Fecha fuera de rango plausible");
        }
        return ValidationResult.pass();
    }
}
