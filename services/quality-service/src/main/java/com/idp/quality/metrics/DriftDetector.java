package com.idp.quality.metrics;

import java.util.List;

/**
 * Deteccion de deriva sobre agregados diarios: error silente por encima del maximo configurado (por defecto 5 %) o
 * tasa STP que cae mas de N puntos frente a la linea base historica. Ignora dias sin muestra minima.
 */
public final class DriftDetector {

    /** Totales de un dia (todas las tipologias de un tenant). */
    public record Day(int total, int stp, int blindSamples, int silentErrors) {
        double stpRate() {
            return total == 0 ? 0 : (double) stp / total;
        }

        double silentRate() {
            return blindSamples == 0 ? 0 : (double) silentErrors / blindSamples;
        }
    }

    public record Verdict(boolean silentErrorDrift, boolean stpDrop, double silentRate, double stpRate,
                          double stpBaseline) {
        public boolean drift() {
            return silentErrorDrift || stpDrop;
        }
    }

    /** Evita que el redondeo binario convierta una caida de exactamente N puntos en deriva. */
    private static final double EPSILON = 1e-9;

    private final double silentErrorMax;
    private final double stpDropPoints;
    private final int minSamples;

    public DriftDetector(double silentErrorMax, double stpDropPoints, int minSamples) {
        this.silentErrorMax = silentErrorMax;
        this.stpDropPoints = stpDropPoints;
        this.minSamples = minSamples;
    }

    /** {@code history}: dias previos de la linea base (el dia evaluado no va incluido). */
    public Verdict evaluate(Day today, List<Day> history) {
        double silent = today.silentRate();
        boolean silentDrift = today.blindSamples() >= minSamples && silent > silentErrorMax + EPSILON;

        int histTotal = 0;
        int histStp = 0;
        for (Day d : history) {
            if (d.total() >= minSamples) {
                histTotal += d.total();
                histStp += d.stp();
            }
        }
        double baseline = histTotal == 0 ? 0 : (double) histStp / histTotal;
        boolean stpDrop = histTotal > 0 && today.total() >= minSamples
            && (baseline - today.stpRate()) * 100.0 > stpDropPoints + EPSILON;
        return new Verdict(silentDrift, stpDrop, silent, today.stpRate(), baseline);
    }
}
