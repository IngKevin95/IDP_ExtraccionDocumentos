package com.idp.quality.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.quality.golden.EvaluationResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gate de CI (SEC-035, fiabilidad AC-07): umbrales minimos, baseline y exit code del CLI. */
class QualityGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path dir;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void streams() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private static EvaluationResult run(double f1, double precision, double recall, double ece) {
        return new EvaluationResult("m/p", 100, 100, f1, precision, recall, f1, 0.2, ece, List.of(), null, List.of());
    }

    private int cli(String... args) {
        return QualityGateCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path write(String name, Object value) throws IOException {
        Path p = dir.resolve(name);
        MAPPER.writeValue(p.toFile(), value);
        return p;
    }

    @Test
    void ac07_pasaCuandoCumpleUmbralesYBaseline() {
        QualityGate.Result r = QualityGate.check(run(0.95, 0.96, 0.94, 0.03),
            new QualityGate.Thresholds(0.9, 0.9, 0.9, 0.05), run(0.94, 0.9, 0.9, 0.04));
        assertThat(r.passed()).isTrue();
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void ac07_fallaSiBajaF1OEmpeoraEceFrenteAlBaseline() {
        QualityGate.Result r = QualityGate.check(run(0.90, 0.9, 0.9, 0.04), QualityGate.Thresholds.defaults(),
            run(0.95, 0.9, 0.9, 0.03));
        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).hasSize(2);
        assertThat(r.violations()).anyMatch(v -> v.contains("f1")).anyMatch(v -> v.contains("ece"));
    }

    @Test
    void fallaPorUmbralesMinimosYPorEceSobreElMaximo() {
        QualityGate.Result r = QualityGate.check(run(0.8, 0.85, 0.7, 0.06),
            new QualityGate.Thresholds(0.9, 0.9, 0.9, 0.05), null);
        assertThat(r.violations()).hasSize(4);
    }

    @Test
    void corridaSinPrediccionesNuncaPasa() {
        EvaluationResult empty = new EvaluationResult("m/p", 0, 10, 0, 0, 0, 0, 0, 0, List.of(), null, List.of());
        assertThat(QualityGate.check(empty, QualityGate.Thresholds.defaults(), null).passed()).isFalse();
    }

    @Test
    void cliDevuelveExitCode0Y1SegunElGate() throws IOException {
        Path good = write("good.json", run(0.95, 0.95, 0.95, 0.02));
        Path bad = write("bad.json", run(0.70, 0.7, 0.7, 0.2));
        Path thresholds = write("t.json", Map.of("minF1", 0.9, "maxEce", 0.05));

        assertThat(cli("gate", "--run", good.toString(), "--thresholds", thresholds.toString()))
            .isEqualTo(QualityGateCli.EXIT_PASS);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("GATE PASS");

        out.reset();
        assertThat(cli("gate", "--run", bad.toString(), "--thresholds", thresholds.toString()))
            .isEqualTo(QualityGateCli.EXIT_FAIL);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("GATE FAIL");

        assertThat(cli("gate", "--run", good.toString(), "--baseline", bad.toString()))
            .isEqualTo(QualityGateCli.EXIT_PASS);
        assertThat(cli("gate", "--run", bad.toString(), "--baseline", good.toString(), "--max-ece", "1"))
            .isEqualTo(QualityGateCli.EXIT_FAIL);
        assertThat(cli("gate", "--run", good.toString(), "--min-f1", "0.99")).isEqualTo(QualityGateCli.EXIT_FAIL);
    }

    @Test
    void cliEvaluaGoldenSetContraPrediccionesYEscribeLaCorrida() throws IOException {
        Path golden = Files.createDirectory(dir.resolve("golden"));
        Files.writeString(golden.resolve("ec-1.json"), "{\"id\":\"ec-1\",\"tipologia\":\"EC\",\"sintetico\":true,"
            + "\"verdad\":{\"radicado\":\"R1\",\"monto\":\"100\"}}");
        Files.writeString(dir.resolve("pred.json"), "{\"modelPromptKey\":\"m/p\",\"predicciones\":["
            + "{\"documentId\":\"ec-1\",\"campo\":\"radicado\",\"valor\":\"R1\",\"confianza\":0.9},"
            + "{\"documentId\":\"ec-1\",\"campo\":\"monto\",\"valor\":\"100\",\"confianza\":0.9}]}");
        Path outFile = dir.resolve("run.json");

        int code = cli("gate", "--golden", golden.toString(), "--predictions", dir.resolve("pred.json").toString(),
            "--out", outFile.toString(), "--min-f1", "0.99", "--max-ece", "0.2");
        assertThat(code).isEqualTo(QualityGateCli.EXIT_PASS);
        EvaluationResult written = MAPPER.readValue(outFile.toFile(), EvaluationResult.class);
        assertThat(written.f1()).isEqualTo(1.0);
        assertThat(written.modelPromptKey()).isEqualTo("m/p");
    }

    @Test
    void cliDevuelveExitCode2PorUsoIndebidoOArchivosInvalidos() throws IOException {
        assertThat(cli()).isEqualTo(QualityGateCli.EXIT_ERROR);
        assertThat(cli("otra-cosa")).isEqualTo(QualityGateCli.EXIT_ERROR);
        assertThat(cli("gate")).isEqualTo(QualityGateCli.EXIT_ERROR);
        assertThat(cli("gate", "--run", dir.resolve("no-existe.json").toString())).isEqualTo(QualityGateCli.EXIT_ERROR);
        Files.writeString(dir.resolve("roto.json"), "{no es json");
        assertThat(cli("gate", "--run", dir.resolve("roto.json").toString())).isEqualTo(QualityGateCli.EXIT_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Error del gate");
    }
}
