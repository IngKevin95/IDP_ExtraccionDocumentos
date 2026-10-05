package com.idp.quality.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Prediccion de un campo para un oficio del golden set, con el score crudo del modelo en [0,1]. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldPrediction(String documentId, String campo, String valor, double confianza) {
}
