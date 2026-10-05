package com.idp.quality.calibration;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Calcula tau_auto y tau_revisar sobre scores ya calibrados por precision objetivo: tau es el score mas bajo tal que
 * la precision acumulada de todas las muestras con score >= tau alcanza el objetivo (maxima cobertura que cumple).
 * Con muestra insuficiente o sin umbral que cumpla, tau_auto queda por encima de 1 (nada se auto-aprueba).
 */
public final class ThresholdSelector {

    /** Valor "inalcanzable": ningun score calibrado en [0,1] lo supera. */
    public static final double NEVER = Math.nextUp(1.0);

    public record Thresholds(double tauAuto, double tauRevisar, boolean attainable, int samples,
                             double precisionAtAuto, double coverageAtAuto) {
    }

    private ThresholdSelector() {
    }

    public static Thresholds select(double[] calibrated, boolean[] correct, double targetAuto, double targetRevisar,
                                    int minSamples) {
        if (calibrated.length != correct.length) {
            throw new IllegalArgumentException("scores y etiquetas deben tener igual longitud");
        }
        if (targetAuto <= 0 || targetAuto > 1 || targetRevisar <= 0 || targetRevisar > targetAuto) {
            throw new IllegalArgumentException("Objetivos de precision invalidos");
        }
        int n = calibrated.length;
        if (n == 0 || n < minSamples) {
            return new Thresholds(NEVER, NEVER, false, n, 0.0, 0.0);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> calibrated[i]).reversed());

        double tauAuto = NEVER;
        double tauRevisar = NEVER;
        double precisionAuto = 0.0;
        double coverageAuto = 0.0;
        int seen = 0;
        int ok = 0;
        int idx = 0;
        while (idx < n) {
            double s = calibrated[order[idx]];
            while (idx < n && calibrated[order[idx]] == s) {
                ok += correct[order[idx]] ? 1 : 0;
                seen++;
                idx++;
            }
            double precision = (double) ok / seen;
            if (precision >= targetAuto) {
                tauAuto = s;
                precisionAuto = precision;
                coverageAuto = (double) seen / n;
            }
            if (precision >= targetRevisar) {
                tauRevisar = s;
            }
        }
        boolean attainable = tauAuto != NEVER;
        if (tauRevisar > tauAuto) {
            tauRevisar = tauAuto;
        }
        return new Thresholds(tauAuto, tauRevisar, attainable, n, precisionAuto, coverageAuto);
    }
}
