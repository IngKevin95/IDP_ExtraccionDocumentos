package com.idp.quality.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Metricas por campo y tipologia contra el golden set, calibracion y umbrales; carga de verdad terreno sintetica. */
class EvaluationEngineTest {

    private static GoldenDocument doc(int i, String tipologia, Map<String, String> verdad) {
        return new GoldenDocument(UUID.randomUUID(), "d-" + i, "d-" + i, tipologia, List.of(), verdad);
    }

    private final EvaluationEngine engine = new EvaluationEngine(new EvaluationEngine.Params(0.9, 0.7, 10, 10));

    @Test
    void precisionYRecallPorCampoYTipologia() {
        List<GoldenDocument> docs = new ArrayList<>();
        List<FieldPrediction> preds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            docs.add(doc(i, "EC", Map.of("monto", "1000", "radicado", "R" + i)));
            // monto: 8 correctos, 1 incorrecto, 1 omitido. radicado: todos correctos.
            if (i < 8) {
                preds.add(new FieldPrediction("d-" + i, "monto", "1000.00", 0.9));
            } else if (i == 8) {
                preds.add(new FieldPrediction("d-" + i, "monto", "999", 0.9));
            }
            preds.add(new FieldPrediction("d-" + i, "radicado", "R" + i, 0.95));
        }
        docs.add(doc(10, "DC", Map.of("monto", "5")));
        preds.add(new FieldPrediction("d-10", "monto", "5", 0.8));

        EvaluationResult r = engine.evaluate("m/p", docs, preds);

        EvaluationResult.FieldMetric ecMonto = r.fields().stream()
            .filter(f -> f.tipologia().equals("EC") && f.campo().equals("monto")).findFirst().orElseThrow();
        assertThat(ecMonto.tp()).isEqualTo(8);
        assertThat(ecMonto.fp()).isEqualTo(1);
        assertThat(ecMonto.fn()).isEqualTo(2); // el incorrecto y el omitido
        assertThat(ecMonto.precision()).isEqualTo(8.0 / 9, within(1e-12));
        assertThat(ecMonto.recall()).isEqualTo(0.8, within(1e-12));
        assertThat(r.fields()).extracting(EvaluationResult.FieldMetric::tipologia).contains("DC");
        // global: tp = 8 + 10 + 1 = 19, fp = 1, fn = 2
        assertThat(r.precision()).isEqualTo(19.0 / 20, within(1e-12));
        assertThat(r.recall()).isEqualTo(19.0 / 21, within(1e-12));
        assertThat(r.truthFields()).isEqualTo(21);
        assertThat(r.accuracy()).isEqualTo(19.0 / 21, within(1e-12));
        assertThat(r.f1()).isBetween(0.9, 1.0);
    }

    @Test
    void campoAlucinadoCuentaComoFalsoPositivo() {
        List<GoldenDocument> docs = List.of(doc(0, "EC", Map.of("monto", "1")));
        List<FieldPrediction> preds = List.of(new FieldPrediction("d-0", "monto", "1", 0.9),
            new FieldPrediction("d-0", "inventado", "x", 0.9));
        EvaluationResult r = engine.evaluate("m/p", docs, preds);
        assertThat(r.fields()).anySatisfy(f -> {
            assertThat(f.campo()).isEqualTo("inventado");
            assertThat(f.fp()).isEqualTo(1);
        });
        assertThat(r.precision()).isEqualTo(0.5);
    }

    @Test
    void calibracionPorCampoReduceElEceYLosUmbralesRespetanLaPrecisionObjetivo() {
        List<GoldenDocument> docs = new ArrayList<>();
        List<FieldPrediction> preds = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            docs.add(doc(i, "EC", Map.of("radicado", "R" + i, "cedula", "C" + i)));
            boolean ok = i % 6 != 0;
            // radicado: confianza separa bien aciertos de errores. cedula: siempre 0.98 aunque falle 1 de 6.
            preds.add(new FieldPrediction("d-" + i, "radicado", ok ? "R" + i : "mal", ok ? 0.97 : 0.3));
            preds.add(new FieldPrediction("d-" + i, "cedula", ok ? "C" + i : "mal", 0.98));
        }
        EvaluationResult r = engine.evaluate("m/p", docs, preds);

        assertThat(r.eceRaw()).isGreaterThan(0.05);
        assertThat(r.eceCalibrated()).isLessThan(0.05).isLessThan(r.eceRaw());
        assertThat(r.calibration().fields()).containsKeys("EC.radicado", "EC.cedula");

        EvaluationResult.ThresholdRow radicado = r.thresholds().stream()
            .filter(t -> t.campo().equals("radicado")).findFirst().orElseThrow();
        EvaluationResult.ThresholdRow cedula = r.thresholds().stream()
            .filter(t -> t.campo().equals("cedula")).findFirst().orElseThrow();
        assertThat(radicado.attainable()).isTrue();
        assertThat(radicado.precisionAtAuto()).isGreaterThanOrEqualTo(0.9);
        assertThat(radicado.scope()).isEqualTo("FIELD");
        assertThat(cedula.attainable()).isFalse(); // precision real 0.83 < objetivo 0.9
    }

    @Test
    void camposConPocaMuestraHeredanLosUmbralesDeLaTipologia() {
        List<GoldenDocument> docs = new ArrayList<>();
        List<FieldPrediction> preds = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            docs.add(doc(i, "EC", Map.of("monto", "M" + i)));
            preds.add(new FieldPrediction("d-" + i, "monto", "M" + i, 0.9));
        }
        docs.add(doc(99, "EC", Map.of("raro", "x")));
        preds.add(new FieldPrediction("d-99", "raro", "x", 0.9));
        EvaluationResult r = engine.evaluate("m/p", docs, preds);
        EvaluationResult.ThresholdRow raro = r.thresholds().stream().filter(t -> t.campo().equals("raro"))
            .findFirst().orElseThrow();
        assertThat(raro.scope()).isEqualTo("TIPOLOGIA");
    }

    @Test
    void coincidenciaNormalizaEspaciosMayusculasYNumeros() {
        assertThat(EvaluationEngine.matches("  Juzgado  1 CIVIL", "juzgado 1 civil")).isTrue();
        assertThat(EvaluationEngine.matches("500000.00", "500000")).isTrue();
        assertThat(EvaluationEngine.matches("500000", "500001")).isFalse();
        assertThat(EvaluationEngine.matches("abc", "abd")).isFalse();
    }

    @Test
    void cargaVerdadTerrenoSinteticaYExigeLaMarca(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.json"), "{\"id\":\"ec-1\",\"tipologia\":\"EC\",\"sintetico\":true,"
            + "\"verdad\":{\"radicado\":\"11001\",\"monto\":123.50,\"tabla\":[1,2]}}");
        GoldenSetLoader loader = new GoldenSetLoader(new ObjectMapper());
        List<GoldenSetLoader.Loaded> loaded = loader.loadDirectory(dir);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).verdad()).containsEntry("monto", "123.5").containsEntry("tabla", "[1,2]");

        Files.writeString(dir.resolve("b.json"), "{\"id\":\"ec-2\",\"tipologia\":\"EC\",\"verdad\":{\"a\":\"b\"}}");
        assertThatThrownBy(() -> loader.loadDirectory(dir)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sintetico");
    }

    @Test
    void predictionRunnerLeePorClaveYBloqueaPathTraversal(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("modelo-a_prompt-v1.json"), "{\"modelPromptKey\":\"modelo-a/prompt-v1\","
            + "\"predicciones\":[{\"documentId\":\"d-0\",\"campo\":\"monto\",\"valor\":\"1\",\"confianza\":0.9}]}");
        FilePredictionRunner runner = new FilePredictionRunner(dir, new ObjectMapper());
        assertThat(runner.run("modelo-a/prompt-v1", List.of())).hasSize(1);
        assertThatThrownBy(() -> runner.run("../../etc/passwd", List.of()))
            .isInstanceOf(ExtractionRunner.RunnerException.class);
        assertThatThrownBy(() -> new FilePredictionRunner(null, new ObjectMapper()).run("x", List.of()))
            .isInstanceOf(ExtractionRunner.RunnerException.class);
    }
}
