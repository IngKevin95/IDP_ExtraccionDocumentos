package com.idp.extraction.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Calibracion isotonica por tipo de campo: curva monotona (x, y) ya ajustada offline (PAV) con
 * interpolacion lineal. Formato JSON:
 * {@code {"id":"isotonic-gemini-1","curves":{"default":{"x":[0,0.5,1],"y":[0,0.4,1]},"decimal":{...}}}}
 */
public final class IsotonicCalibrator implements Calibrator {

    /** Curva monotona no decreciente. */
    public record Curve(double[] x, double[] y) {
        public Curve {
            if (x.length != y.length || x.length < 2) {
                throw new IllegalArgumentException("La curva requiere al menos dos puntos de igual longitud");
            }
            for (int i = 1; i < x.length; i++) {
                if (x[i] <= x[i - 1]) {
                    throw new IllegalArgumentException("x debe ser estrictamente creciente");
                }
                if (y[i] < y[i - 1]) {
                    throw new IllegalArgumentException("y debe ser monotona no decreciente (isotonica)");
                }
            }
            x = x.clone();
            y = y.clone();
        }

        double at(double v) {
            if (v <= x[0]) {
                return y[0];
            }
            if (v >= x[x.length - 1]) {
                return y[y.length - 1];
            }
            int lo = 0;
            int hi = x.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (x[mid] <= v) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double t = (v - x[lo]) / (x[hi] - x[lo]);
            return y[lo] + t * (y[hi] - y[lo]);
        }
    }

    private static final String DEFAULT = "default";

    private final String id;
    private final Map<String, Curve> curves;

    public IsotonicCalibrator(String id, Map<String, Curve> curves) {
        if (!curves.containsKey(DEFAULT)) {
            throw new IllegalArgumentException("Se requiere una curva 'default'");
        }
        this.id = id;
        this.curves = Map.copyOf(curves);
    }

    public static IsotonicCalibrator fromJson(InputStream in) {
        try {
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, Curve> curves = new HashMap<>();
            for (Map.Entry<String, JsonNode> e : root.path("curves").properties()) {
                curves.put(e.getKey(), new Curve(toArray(e.getValue().path("x")), toArray(e.getValue().path("y"))));
            }
            return new IsotonicCalibrator(root.path("id").asText("isotonic"), curves);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double[] toArray(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).asDouble();
        }
        return out;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public double calibrate(String fieldType, double raw) {
        Curve c = curves.getOrDefault(fieldType, curves.get(DEFAULT));
        return Math.max(0.0, Math.min(1.0, c.at(Math.max(0.0, Math.min(1.0, raw)))));
    }
}
