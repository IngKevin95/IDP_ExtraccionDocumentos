package com.idp.quality.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Archivo de una corrida de evaluacion: predicciones de un par modelo+prompt sobre el golden set. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictionRun(String modelPromptKey, List<FieldPrediction> predicciones) {
}
