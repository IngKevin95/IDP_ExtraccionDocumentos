package com.idp.quality.calibration;

/** Metricas de calibracion. */
public final class CalibrationMetrics {

    private CalibrationMetrics() {
    }

    /**
     * ECE (Expected Calibration Error) con bins de igual ancho: suma ponderada de |exactitud - confianza media|
     * por bin. Cero es calibracion perfecta; el objetivo de produccion es menor a 0,05.
     */
    public static double ece(double[] probabilities, boolean[] correct, int bins) {
        if (probabilities.length != correct.length) {
            throw new IllegalArgumentException("probabilidades y etiquetas deben tener igual longitud");
        }
        if (bins < 1) {
            throw new IllegalArgumentException("bins debe ser >= 1");
        }
        int n = probabilities.length;
        if (n == 0) {
            return 0.0;
        }
        double[] sumConf = new double[bins];
        double[] sumAcc = new double[bins];
        int[] count = new int[bins];
        for (int i = 0; i < n; i++) {
            double p = Math.max(0.0, Math.min(1.0, probabilities[i]));
            int b = Math.min(bins - 1, (int) (p * bins));
            sumConf[b] += p;
            sumAcc[b] += correct[i] ? 1 : 0;
            count[b]++;
        }
        double ece = 0;
        for (int b = 0; b < bins; b++) {
            if (count[b] > 0) {
                ece += Math.abs(sumAcc[b] - sumConf[b]) / n;
            }
        }
        return ece;
    }
}
