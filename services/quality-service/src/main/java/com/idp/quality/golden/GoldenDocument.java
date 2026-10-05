package com.idp.quality.golden;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Oficio sintetico del golden set con su verdad terreno (campo a valor normalizado como texto). */
public record GoldenDocument(UUID id, String externalId, String nombre, String tipologia, List<String> tags,
                             Map<String, String> verdad) {

    /** Clave con la que las predicciones de una corrida referencian este oficio. */
    public String key() {
        return externalId != null && !externalId.isBlank() ? externalId : id.toString();
    }
}
