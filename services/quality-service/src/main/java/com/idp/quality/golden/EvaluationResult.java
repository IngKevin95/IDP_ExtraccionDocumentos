package com.idp.quality.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.idp.quality.calibration.IsotonicCalibrator;
import java.util.List;
import java.util.Map;

/** Resultado de evaluar un par modelo+prompt contra el golden set. Se persiste y es la entrada del gate de CI. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationResult(String modelPromptKey, int samples, int truthFields, double accuracy,
                               double precision, double recall, double f1, double eceRaw, double eceCalibrated,
                               List<FieldMetric> fields, CalibrationModel calibration,
                               List<ThresholdRow> thresholds) {

    /** Precision y recall de un campo de una tipologia. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldMetric(String tipologia, String campo, int tp, int fp, int fn, double precision,
                              double recall) {
    }

    /** Curvas isotonicas: una global y una por campo con muestra suficiente (clave tipologia.campo). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalibrationModel(List<IsotonicCalibrator.Point> global,
                                   Map<String, List<IsotonicCalibrator.Point>> fields) {

        public IsotonicCalibrator forField(String tipologia, String campo) {
            List<IsotonicCalibrator.Point> pts = fields.get(tipologia + "." + campo);
            return IsotonicCalibrator.of(pts != null ? pts : global);
        }
    }

    /** Umbrales de un campo (scope FIELD) o de la tipologia completa (scope TIPOLOGIA) por muestra insuficiente. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThresholdRow(String tipologia, String campo, String scope, double tauAuto, double tauRevisar,
                               boolean attainable, int samples, double precisionAtAuto, double coverageAtAuto,
                               double targetPrecision) {
    }
}
