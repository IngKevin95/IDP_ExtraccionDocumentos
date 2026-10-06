package com.idp.quality.golden;

import java.util.List;
import java.util.UUID;

/**
 * Puerto que ejecuta un par modelo+prompt sobre oficios sinteticos de un tenant y devuelve sus predicciones. Es de solo lectura
 * respecto a produccion: nunca recibe ni escribe documentos reales.
 */
public interface ExtractionRunner {

    List<FieldPrediction> run(UUID tenantId, String modelPromptKey, List<GoldenDocument> documents);

    /** La corrida no pudo ejecutarse (sin resultados registrados, proveedor caido). */
    class RunnerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RunnerException(String message) {
            super(message);
        }
    }
}
