package com.idp.extraction.core;

import java.util.List;

/** Evidencia de un valor (SEC-031): pagina, cita literal y caja delimitadora normalizada [x, y, ancho, alto]. */
public record Evidence(Integer page, String quote, List<Double> bbox) {

    public Evidence {
        bbox = bbox == null ? null : List.copyOf(bbox);
    }

    /** Evidencia completa: pagina, cita literal no vacia y caja de 4 coordenadas. */
    public boolean complete() {
        return page != null && page > 0 && quote != null && !quote.isBlank() && bbox != null && bbox.size() == 4;
    }
}
