package com.idp.quality.calibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Regresion isotonica (PAV, pool adjacent violators) de la correccion observada sobre el score crudo del modelo.
 * Funcion no decreciente, lineal por tramos entre los centros de bloque. Es matematica pura: sin estado compartido.
 */
public final class IsotonicCalibrator {

    /** Punto de la curva de calibracion: score crudo medio del bloque y probabilidad calibrada. */
    public record Point(double x, double y) {
    }

    private final double[] xs;
    private final double[] ys;

    private IsotonicCalibrator(double[] xs, double[] ys) {
        this.xs = xs;
        this.ys = ys;
    }

    /** Ajusta la curva con scores crudos en [0,1] y la etiqueta de acierto (true = extraccion correcta). */
    public static IsotonicCalibrator fit(double[] scores, boolean[] correct) {
        if (scores.length != correct.length) {
            throw new IllegalArgumentException("scores y etiquetas deben tener igual longitud");
        }
        if (scores.length == 0) {
            return new IsotonicCalibrator(new double[0], new double[0]);
        }
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> scores[i]));

        // Agrupa scores empatados: una misma entrada debe tener una misma salida.
        List<double[]> blocks = new ArrayList<>(); // {sumX, sumY, weight}
        int i = 0;
        while (i < order.length) {
            double s = scores[order[i]];
            double sumY = 0;
            int w = 0;
            while (i < order.length && scores[order[i]] == s) {
                sumY += correct[order[i]] ? 1 : 0;
                w++;
                i++;
            }
            blocks.add(new double[] {s * w, sumY, w});
            pool(blocks);
        }
        double[] bx = new double[blocks.size()];
        double[] by = new double[blocks.size()];
        for (int b = 0; b < bx.length; b++) {
            double[] blk = blocks.get(b);
            bx[b] = blk[0] / blk[2];
            by[b] = blk[1] / blk[2];
        }
        return new IsotonicCalibrator(bx, by);
    }

    private static void pool(List<double[]> blocks) {
        while (blocks.size() >= 2) {
            double[] last = blocks.get(blocks.size() - 1);
            double[] prev = blocks.get(blocks.size() - 2);
            if (prev[1] / prev[2] <= last[1] / last[2]) {
                return;
            }
            prev[0] += last[0];
            prev[1] += last[1];
            prev[2] += last[2];
            blocks.remove(blocks.size() - 1);
        }
    }

    public static IsotonicCalibrator of(List<Point> points) {
        double[] x = new double[points.size()];
        double[] y = new double[points.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = points.get(i).x();
            y[i] = points.get(i).y();
            if (i > 0 && (x[i] <= x[i - 1] || y[i] < y[i - 1])) {
                throw new IllegalArgumentException("Curva isotonica invalida: x creciente e y no decreciente");
            }
        }
        return new IsotonicCalibrator(x, y);
    }

    /** Probabilidad calibrada para un score crudo; sin datos de ajuste devuelve el score acotado a [0,1]. */
    public double predict(double score) {
        if (xs.length == 0) {
            return clamp(score);
        }
        if (score <= xs[0]) {
            return ys[0];
        }
        int last = xs.length - 1;
        if (score >= xs[last]) {
            return ys[last];
        }
        int lo = 0;
        int hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] <= score) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double t = (score - xs[lo]) / (xs[hi] - xs[lo]);
        return ys[lo] + t * (ys[hi] - ys[lo]);
    }

    public List<Point> points() {
        List<Point> out = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            out.add(new Point(xs[i], ys[i]));
        }
        return out;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
