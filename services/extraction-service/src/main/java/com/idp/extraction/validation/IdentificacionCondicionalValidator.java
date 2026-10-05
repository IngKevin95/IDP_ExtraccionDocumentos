package com.idp.extraction.validation;

import java.util.Optional;

/** Rutea a {@link CedulaValidator} o {@link NitValidator} segun el tipo de identificacion de la fila. */
public final class IdentificacionCondicionalValidator implements FieldValidator {

    private final CedulaValidator cedula;
    private final NitValidator nit;

    public IdentificacionCondicionalValidator(CedulaValidator cedula, NitValidator nit) {
        this.cedula = cedula;
        this.nit = nit;
    }

    @Override
    public String id() {
        return "cedula_nit_condicional";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        Optional<String> tipo = TipoIdentificacionValidator.normalize(ctx.rowOrDoc("tipo_identificacion"));
        if (tipo.isEmpty()) {
            return ValidationResult.fail("TIPO_DOC_INDETERMINADO",
                "Sin tipo de identificacion valido no se puede validar el numero");
        }
        return "NIT".equals(tipo.get()) ? nit.validate(value, ctx) : cedula.validate(value, ctx);
    }
}
