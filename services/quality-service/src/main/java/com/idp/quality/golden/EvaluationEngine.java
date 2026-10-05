package com.idp.quality.golden;

import com.idp.quality.calibration.CalibrationMetrics;
import com.idp.quality.calibration.IsotonicCalibrator;
import com.idp.quality.calibration.ThresholdSelector;
import com.idp.quality.golden.EvaluationResult.CalibrationModel;
import com.idp.quality.golden.EvaluationResult.FieldMetric;
import com.idp.quality.golden.EvaluationResult.ThresholdRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evalua predicciones contra la verdad terreno del golden set: precision y recall por campo y tipologia, calibracion
 * isotonica (ECE antes y despues) y umbrales por precision objetivo. Logica pura, sin E/S.
 */
public final class EvaluationEngine {

    public record Params(double targetAuto, double targetRevisar, int minFieldSamples, int eceBins) {
        public static Params defaults() {
            return new Params(0.98, 0.85, 30, 10);
        }
    }

    private record Sample(String tipologia, String campo, double score, boolean correct) {
    }

    private static final class Counts {
        int tp;
        int fp;
        int fn;
    }

    private final Params params;

    public EvaluationEngine(Params params) {
        this.params = params;
    }

    public EvaluationResult evaluate(String modelPromptKey, List<GoldenDocument> docs,
                                     List<FieldPrediction> predictions) {
        Map<String, Map<String, FieldPrediction>> byDoc = new HashMap<>();
        for (FieldPrediction p : predictions) {
            if (p == null || p.documentId() == null || p.campo() == null) {
                continue;
            }
            byDoc.computeIfAbsent(p.documentId(), k -> new HashMap<>()).putIfAbsent(p.campo(), p);
        }
        List<Sample> samples = new ArrayList<>();
        Map<String, Counts> counts = new TreeMap<>();
        int truthFields = 0;
        int correctFields = 0;
        for (GoldenDocument doc : docs) {
            Map<String, FieldPrediction> preds = byDoc.getOrDefault(doc.key(), Map.of());
            for (Map.Entry<String, String> truth : doc.verdad().entrySet()) {
                truthFields++;
                Counts c = counts.computeIfAbsent(doc.tipologia() + "." + truth.getKey(), k -> new Counts());
                FieldPrediction p = preds.get(truth.getKey());
                if (p == null || p.valor() == null || p.valor().isBlank()) {
                    c.fn++;
                    continue;
                }
                boolean ok = matches(truth.getValue(), p.valor());
                samples.add(new Sample(doc.tipologia(), truth.getKey(), clamp(p.confianza()), ok));
                if (ok) {
                    c.tp++;
                    correctFields++;
                } else {
                    c.fp++;
                    c.fn++;
                }
            }
            // Campos extraidos que no existen en la verdad: alucinaciones (falso positivo).
            for (FieldPrediction p : preds.values()) {
                if (!doc.verdad().containsKey(p.campo()) && p.valor() != null && !p.valor().isBlank()) {
                    counts.computeIfAbsent(doc.tipologia() + "." + p.campo(), k -> new Counts()).fp++;
                    samples.add(new Sample(doc.tipologia(), p.campo(), clamp(p.confianza()), false));
                }
            }
        }
        return build(modelPromptKey, samples, counts, truthFields, correctFields);
    }

    private EvaluationResult build(String key, List<Sample> samples, Map<String, Counts> counts, int truthFields,
                                   int correctFields) {
        double[] raw = samples.stream().mapToDouble(Sample::score).toArray();
        boolean[] ok = new boolean[samples.size()];
        for (int i = 0; i < ok.length; i++) {
            ok[i] = samples.get(i).correct();
        }
        IsotonicCalibrator global = IsotonicCalibrator.fit(raw, ok);

        Map<String, List<Integer>> idxByField = new LinkedHashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            idxByField.computeIfAbsent(s.tipologia() + "." + s.campo(), k -> new ArrayList<>()).add(i);
        }
        Map<String, IsotonicCalibrator> perField = new TreeMap<>();
        for (Map.Entry<String, List<Integer>> e : idxByField.entrySet()) {
            if (e.getValue().size() >= params.minFieldSamples()) {
                perField.put(e.getKey(), IsotonicCalibrator.fit(pick(raw, e.getValue()), pick(ok, e.getValue())));
            }
        }
        double[] calibrated = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            Sample s = samples.get(i);
            IsotonicCalibrator c = perField.getOrDefault(s.tipologia() + "." + s.campo(), global);
            calibrated[i] = c.predict(raw[i]);
        }

        List<FieldMetric> fields = new ArrayList<>();
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (Map.Entry<String, Counts> e : counts.entrySet()) {
            String[] k = split(e.getKey());
            Counts c = e.getValue();
            fields.add(new FieldMetric(k[0], k[1], c.tp, c.fp, c.fn, ratio(c.tp, c.tp + c.fp),
                ratio(c.tp, c.tp + c.fn)));
            tp += c.tp;
            fp += c.fp;
            fn += c.fn;
        }
        double precision = ratio(tp, tp + fp);
        double recall = ratio(tp, tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

        List<ThresholdRow> thresholds = thresholds(samples, calibrated, idxByField);
        Map<String, List<IsotonicCalibrator.Point>> fieldPoints = new TreeMap<>();
        perField.forEach((k, v) -> fieldPoints.put(k, v.points()));
        return new EvaluationResult(key, samples.size(), truthFields,
            truthFields == 0 ? 0 : (double) correctFields / truthFields, precision, recall, f1,
            CalibrationMetrics.ece(raw, ok, params.eceBins()),
            CalibrationMetrics.ece(calibrated, ok, params.eceBins()), fields,
            new CalibrationModel(global.points(), fieldPoints), thresholds);
    }

    private List<ThresholdRow> thresholds(List<Sample> samples, double[] calibrated,
                                          Map<String, List<Integer>> idxByField) {
        Map<String, List<Integer>> idxByTipologia = new TreeMap<>();
        for (int i = 0; i < samples.size(); i++) {
            idxByTipologia.computeIfAbsent(samples.get(i).tipologia(), k -> new ArrayList<>()).add(i);
        }
        Map<String, ThresholdSelector.Thresholds> tipologiaLevel = new TreeMap<>();
        for (Map.Entry<String, List<Integer>> e : idxByTipologia.entrySet()) {
            tipologiaLevel.put(e.getKey(), select(samples, calibrated, e.getValue()));
        }
        List<ThresholdRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : new TreeMap<>(idxByField).entrySet()) {
            String[] k = split(e.getKey());
            boolean enough = e.getValue().size() >= params.minFieldSamples();
            ThresholdSelector.Thresholds t = enough ? select(samples, calibrated, e.getValue())
                : tipologiaLevel.get(k[0]);
            rows.add(new ThresholdRow(k[0], k[1], enough ? "FIELD" : "TIPOLOGIA", t.tauAuto(), t.tauRevisar(),
                t.attainable(), t.samples(), t.precisionAtAuto(), t.coverageAtAuto(), params.targetAuto()));
        }
        return rows;
    }

    private ThresholdSelector.Thresholds select(List<Sample> samples, double[] calibrated, List<Integer> idx) {
        double[] cal = pick(calibrated, idx);
        boolean[] ok = new boolean[idx.size()];
        for (int i = 0; i < ok.length; i++) {
            ok[i] = samples.get(idx.get(i)).correct();
        }
        return ThresholdSelector.select(cal, ok, params.targetAuto(), params.targetRevisar(),
            params.minFieldSamples());
    }

    /** Igualdad de valores tras normalizar espacios y mayusculas; los numeros se comparan por valor. */
    public static boolean matches(String truth, String predicted) {
        String a = normalize(truth);
        String b = normalize(predicted);
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static double[] pick(double[] src, List<Integer> idx) {
        double[] out = new double[idx.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = src[idx.get(i)];
        }
        return out;
    }

    private static boolean[] pick(boolean[] src, List<Integer> idx) {
        boolean[] out = new boolean[idx.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = src[idx.get(i)];
        }
        return out;
    }

    private static String[] split(String key) {
        int dot = key.indexOf('.');
        return new String[] {key.substring(0, dot), key.substring(dot + 1)};
    }

    private static double ratio(int num, int den) {
        return den == 0 ? 0.0 : (double) num / den;
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }
}
