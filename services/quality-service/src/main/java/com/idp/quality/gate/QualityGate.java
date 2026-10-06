package com.idp.quality.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.idp.quality.golden.EvaluationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Gate de riesgo de modelo en CI (SEC-035): compara una corrida de evaluacion contra umbrales minimos y, si se
 * entrega, contra el baseline vigente (F1 no menor y ECE no mayor). Es logica pura; el exit code lo fija el CLI.
 */
public final class QualityGate {

    /** Umbrales minimos; un valor nulo no se exige. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Thresholds(Double minF1, Double minPrecision, Double minRecall, Double maxEce) {
        public static Thresholds defaults() {
            return new Thresholds(null, null, null, 0.05);
        }
    }

    public record Result(boolean passed, List<String> violations) {
    }

    private static final double TOLERANCE = 1e-9;

    private QualityGate() {
    }

    public static Result check(EvaluationResult run, Thresholds t, EvaluationResult baseline) {
        List<String> v = new ArrayList<>();
        if (run.samples() == 0) {
            v.add("la corrida no tiene predicciones evaluables");
        }
        below(v, "f1", run.f1(), t.minF1());
        below(v, "precision", run.precision(), t.minPrecision());
        below(v, "recall", run.recall(), t.minRecall());
        if (t.maxEce() != null && run.eceCalibrated() > t.maxEce() + TOLERANCE) {
            v.add("ece " + fmt(run.eceCalibrated()) + " supera el maximo " + fmt(t.maxEce()));
        }
        if (baseline != null) {
            if (run.f1() + TOLERANCE < baseline.f1()) {
                v.add("f1 " + fmt(run.f1()) + " es menor al baseline " + fmt(baseline.f1()));
            }
            if (run.eceCalibrated() > baseline.eceCalibrated() + TOLERANCE) {
                v.add("ece " + fmt(run.eceCalibrated()) + " es mayor al baseline " + fmt(baseline.eceCalibrated()));
            }
        }
        return new Result(v.isEmpty(), List.copyOf(v));
    }

    private static void below(List<String> v, String name, double value, Double min) {
        if (min != null && value + TOLERANCE < min) {
            v.add(name + " " + fmt(value) + " es menor al minimo " + fmt(min));
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.4f", d);
    }
}
