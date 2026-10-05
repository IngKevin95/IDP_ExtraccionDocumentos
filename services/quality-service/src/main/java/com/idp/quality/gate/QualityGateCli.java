package com.idp.quality.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.quality.golden.EvaluationEngine;
import com.idp.quality.golden.EvaluationResult;
import com.idp.quality.golden.FieldPrediction;
import com.idp.quality.golden.GoldenDocument;
import com.idp.quality.golden.GoldenSetLoader;
import com.idp.quality.golden.PredictionRun;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gate de CI ejecutable: {@code gate --golden <dir> --predictions <archivo> [--thresholds <archivo>]
 * [--baseline <corrida.json>] [--out <corrida.json>] [--min-f1 x] [--min-precision x] [--min-recall x] [--max-ece x]}
 * o {@code gate --run <corrida.json> ...} para reutilizar una corrida ya evaluada.
 * Exit code: 0 supera el gate, 1 lo incumple, 2 error de uso o de lectura. No toca ninguna base de datos.
 */
public final class QualityGateCli {

    public static final int EXIT_PASS = 0;
    public static final int EXIT_FAIL = 1;
    public static final int EXIT_ERROR = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QualityGateCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || !"gate".equals(args[0])) {
                err.println("Uso: gate --golden <dir> --predictions <archivo> | --run <corrida.json> "
                    + "[--thresholds <archivo>] [--baseline <corrida.json>] [--out <archivo>] "
                    + "[--min-f1 x] [--min-precision x] [--min-recall x] [--max-ece x]");
                return EXIT_ERROR;
            }
            Map<String, String> opts = parse(args);
            EvaluationResult run = opts.containsKey("run")
                ? MAPPER.readValue(Path.of(opts.get("run")).toFile(), EvaluationResult.class)
                : evaluate(opts);
            if (opts.containsKey("out")) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(opts.get("out")).toFile(), run);
            }
            EvaluationResult baseline = opts.containsKey("baseline")
                ? MAPPER.readValue(Path.of(opts.get("baseline")).toFile(), EvaluationResult.class) : null;
            QualityGate.Result result = QualityGate.check(run, thresholds(opts), baseline);
            out.printf(java.util.Locale.ROOT, "modelPromptKey=%s f1=%.4f precision=%.4f recall=%.4f ece=%.4f%n",
                run.modelPromptKey(), run.f1(), run.precision(), run.recall(), run.eceCalibrated());
            if (result.passed()) {
                out.println("GATE PASS");
                return EXIT_PASS;
            }
            result.violations().forEach(v -> out.println("GATE FAIL: " + v));
            return EXIT_FAIL;
        } catch (IOException | RuntimeException e) {
            err.println("Error del gate: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static EvaluationResult evaluate(Map<String, String> opts) throws IOException {
        String golden = require(opts, "golden");
        String predictions = require(opts, "predictions");
        List<GoldenDocument> docs = new ArrayList<>();
        for (GoldenSetLoader.Loaded l : new GoldenSetLoader(MAPPER).loadDirectory(Path.of(golden))) {
            docs.add(new GoldenDocument(UUID.nameUUIDFromBytes(l.externalId().getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), l.externalId(), l.nombre(), l.tipologia(), l.tags(),
                l.verdad()));
        }
        PredictionRun file = MAPPER.readValue(Path.of(predictions).toFile(), PredictionRun.class);
        List<FieldPrediction> preds = file.predicciones() == null ? List.of() : file.predicciones();
        String key = file.modelPromptKey() == null ? "desconocido" : file.modelPromptKey();
        return new EvaluationEngine(EvaluationEngine.Params.defaults()).evaluate(key, docs, preds);
    }

    private static QualityGate.Thresholds thresholds(Map<String, String> opts) throws IOException {
        QualityGate.Thresholds base = opts.containsKey("thresholds")
            ? MAPPER.readValue(Path.of(opts.get("thresholds")).toFile(), QualityGate.Thresholds.class)
            : QualityGate.Thresholds.defaults();
        return new QualityGate.Thresholds(
            opts.containsKey("min-f1") ? Double.valueOf(opts.get("min-f1")) : base.minF1(),
            opts.containsKey("min-precision") ? Double.valueOf(opts.get("min-precision")) : base.minPrecision(),
            opts.containsKey("min-recall") ? Double.valueOf(opts.get("min-recall")) : base.minRecall(),
            opts.containsKey("max-ece") ? Double.valueOf(opts.get("max-ece")) : base.maxEce());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Argumento invalido: " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        if (!opts.containsKey("run") && !(opts.containsKey("golden") && opts.containsKey("predictions"))) {
            throw new IllegalArgumentException("Indique --run o --golden con --predictions");
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null) {
            throw new IllegalArgumentException("Falta --" + name);
        }
        return v;
    }
}
