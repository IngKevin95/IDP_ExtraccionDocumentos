package com.idp.extraction.validation;

import java.util.Locale;
import java.util.Optional;

/** Tipos de identificacion soportados: CC, CE y NIT (tolera puntos y espacios: "C.C."). */
public final class TipoIdentificacionValidator implements FieldValidator {

    @Override
    public String id() {
        return "tipo_doc_valido";
    }

    /** Tipo normalizado (CC, CE, NIT) o vacio si no es soportado. */
    public static Optional<String> normalize(String value) {
        if (value == null || value.length() > 16) {
            return Optional.empty();
        }
        String v = value.replace(".", "").replace(" ", "").toUpperCase(Locale.ROOT);
        return switch (v) {
            case "CC", "CE", "NIT" -> Optional.of(v);
            default -> Optional.empty();
        };
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        return normalize(value).isPresent()
            ? ValidationResult.pass()
            : ValidationResult.fail("TIPO_DOC_NO_SOPORTADO", "Tipo de identificacion no soportado");
    }
}
