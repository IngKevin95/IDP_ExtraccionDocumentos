package com.idp.extraction.reliability;

import com.idp.extraction.core.ExtractedValue;
import com.idp.extraction.typology.FieldDef;
import com.idp.extraction.validation.ValidationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Score por campo y ruteo en tres tramos (ADR-0012). Combina confianza declarada por el modelo,
 * grounding contra la capa de texto, evidencia y validadores; calibra la senal conjunta y aplica
 * despues las reglas duras (validador fallido, falta de evidencia, texto no hallado).
 */
public final class ReliabilityEngine {

    public enum Decision { AUTO, CASCADE, REVIEW }

    /** Tope de score ante un validador duro fallido: siempre bajo cualquier tau_revisar de la tipologia. */
    public static final double VALIDATOR_FAIL_CAP = 0.2;
    /** Factor aplicado cuando el valor critico no aparece en la capa de texto nativa. */
    static final double NOT_FOUND_FACTOR = 0.5;
    private static final double BASE_WITHOUT_CONFIDENCE = 0.5;

    public record FieldScore(double score, boolean validatorFailed, boolean evidenceMissing,
                             boolean confidenceMissing, Grounding.Status grounding, List<String> flags) {
        public FieldScore {
            flags = List.copyOf(flags);
        }
    }

    private final Calibrator calibrator;

    public ReliabilityEngine(Calibrator calibrator) {
        this.calibrator = calibrator;
    }

    public Calibrator calibrator() {
        return calibrator;
    }

    public FieldScore score(FieldDef def, ExtractedValue value, List<ValidationResult> validations,
                            Grounding.Status grounding) {
        List<String> flags = new ArrayList<>();
        if (value == null || !value.present()) {
            flags.add("VALOR_AUSENTE");
            return new FieldScore(0.0, false, false, true, grounding, flags);
        }
        boolean hasConf = value.confidence() != null;
        double base = hasConf ? clamp(value.confidence()) : BASE_WITHOUT_CONFIDENCE;
        if (!hasConf) {
            flags.add("SIN_CONFIANZA_DEL_MODELO");
        }
        boolean failed = validations.stream().anyMatch(ValidationResult::failed);
        boolean allPass = !failed && validations.stream().anyMatch(ValidationResult::passed);
        if (allPass) {
            base += hasConf ? 0.05 : 0.20;
        }
        if (grounding == Grounding.Status.FOUND) {
            base += hasConf ? 0.03 : 0.20;
        }
        double score = calibrator.calibrate(def.type().name().toLowerCase(java.util.Locale.ROOT), clamp(base));
        if (grounding == Grounding.Status.NOT_FOUND) {
            score *= NOT_FOUND_FACTOR;
            flags.add("TEXTO_NO_HALLADO_EN_CAPA_NATIVA");
        }
        boolean evidenceMissing = value.evidence() == null || !value.evidence().complete();
        if (evidenceMissing) {
            score = Math.min(score, (def.umbralRevisar() + def.umbralAuto()) / 2.0);
            flags.add("AUSENCIA_DE_EVIDENCIA");
        }
        if (failed) {
            score = Math.min(score, VALIDATOR_FAIL_CAP);
            flags.add("VALIDADOR_FALLIDO");
        }
        return new FieldScore(clamp(score), failed, evidenceMissing, !hasConf, grounding, flags);
    }

    /** Tramo del campo segun sus umbrales: un validador duro fallido siempre va a revision. */
    public Decision route(FieldDef def, FieldScore s) {
        if (s.validatorFailed()) {
            return Decision.REVIEW;
        }
        if (s.score() >= def.umbralAuto()) {
            return Decision.AUTO;
        }
        if (s.score() >= def.umbralRevisar()) {
            return Decision.CASCADE;
        }
        return Decision.REVIEW;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
