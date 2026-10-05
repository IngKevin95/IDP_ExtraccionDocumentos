package com.idp.extraction.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registro de validadores por id de YAML. Toda referencia de la tipologia debe existir aqui. */
public final class ValidatorRegistry {

    private final Map<String, FieldValidator> byId = new LinkedHashMap<>();

    public ValidatorRegistry(List<FieldValidator> validators) {
        for (FieldValidator v : validators) {
            if (byId.putIfAbsent(v.id(), v) != null) {
                throw new IllegalArgumentException("Validador duplicado: " + v.id());
            }
        }
    }

    /** Catalogo completo de validadores del dominio. */
    public static ValidatorRegistry defaults(JuzgadoValidator juzgado) {
        CedulaValidator cedula = new CedulaValidator();
        NitValidator nit = new NitValidator();
        return new ValidatorRegistry(List.of(
            new RadicadoValidator(), juzgado, new MontoValidator(), new DateCoherenceValidator(),
            new TipoIdentificacionValidator(), cedula, nit,
            new IdentificacionCondicionalValidator(cedula, nit), new TipologiaValidaValidator()));
    }

    public static ValidatorRegistry defaults() {
        return defaults(JuzgadoValidator.withDefaultCatalog());
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public Optional<FieldValidator> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}
