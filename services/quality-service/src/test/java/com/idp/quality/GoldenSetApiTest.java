package com.idp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.quality.golden.FieldPrediction;
import com.idp.quality.golden.GoldenDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** quality-service spec: AC-04 (evaluacion asincrona), T-10 (CRUD), calibracion, umbrales, replay e importacion. */
class GoldenSetApiTest extends AbstractQualityTest {

    private static final Path GOLDEN_DIR = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("golden");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("quality.golden-set.directory", GOLDEN_DIR::toString);
    }

    private static final String BASE = "/v1/quality/reports/golden-set";

    private String create(String tenant, String user, String nombre, String payload) throws Exception {
        JsonNode r = body(mvc.perform(post(BASE).with(token(tenant, user)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nombre\":\"" + nombre + "\",\"tipologia\":\"EC\",\"tags\":[\"base\"],"
                + "\"payload_sintetico_json\":" + payload + "}")).andExpect(status().isCreated()).andReturn());
        return r.get("id").asText();
    }

    private JsonNode awaitJob(String tenant, String user, String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode s = body(mvc.perform(get(BASE + "/evaluations/" + jobId).with(token(tenant, user)))
                .andExpect(status().isOk()).andReturn());
            if (!"QUEUED".equals(s.get("status").asText()) && !"RUNNING".equals(s.get("status").asText())) {
                return s;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("La corrida no termino");
    }

    /** 40 oficios con 2 campos: "radicado" bien separado por confianza y "monto" sobreconfiado. */
    private void seed(String tenant, String user) throws Exception {
        for (int i = 0; i < 40; i++) {
            create(tenant, user, "ec-" + i, "{\"radicado\":\"R" + i + "\",\"monto\":" + (1000 + i) + "}");
        }
    }

    private static List<FieldPrediction> predictions(List<GoldenDocument> docs, double radicadoWrongRate,
                                                      int montoWrongEvery) {
        List<FieldPrediction> out = new ArrayList<>();
        int n = 0;
        for (GoldenDocument d : docs) {
            boolean radOk = n % 10 >= radicadoWrongRate * 10;
            out.add(new FieldPrediction(d.key(), "radicado", radOk ? d.verdad().get("radicado") : "X",
                radOk ? 0.97 : 0.4));
            boolean montoOk = n % montoWrongEvery != 0;
            out.add(new FieldPrediction(d.key(), "monto", montoOk ? d.verdad().get("monto") : "1", 0.95));
            n++;
        }
        return out;
    }

    @Test
    void t10_crudDelGoldenSetEnLasDosRutas() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        String id = create(tenant, user, "Oficio ficticio EC", "{\"radicado\":\"11001202300000000000001\","
            + "\"monto\":500000.00}");

        JsonNode list = body(mvc.perform(get("/v1/quality/golden-set").with(token(tenant, user)))
            .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(new java.math.BigDecimal(list.get(0).get("payload_sintetico_json").get("monto").asText()))
            .isEqualByComparingTo("500000.00");
        mvc.perform(get(BASE + "/" + id).with(token(tenant, user))).andExpect(status().isOk());

        String other = UUID.randomUUID().toString();
        String otherUser = steward(other);
        mvc.perform(get(BASE + "/" + id).with(token(other, otherUser))).andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/" + id).with(token(other, otherUser))).andExpect(status().isNotFound());

        mvc.perform(delete(BASE + "/" + id).with(token(tenant, user))).andExpect(status().isNoContent());
        assertThat(body(mvc.perform(get(BASE).with(token(tenant, user))).andReturn())).isEmpty();
    }

    @Test
    void t10_rechazaDocumentosInvalidos() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        mvc.perform(post(BASE).with(token(tenant, user)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nombre\":\"x\",\"tipologia\":\"ZZ\",\"payload_sintetico_json\":{\"a\":\"b\"}}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(token(tenant, user)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nombre\":\"x\",\"tipologia\":\"EC\",\"payload_sintetico_json\":{}}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(token(tenant, user)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nombre\":\"x\",\"tipologia\":\"EC\",\"payload_sintetico_json\":{\"Campo Raro\":\"b\"}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ac04_evaluateResponde202YEvaluaAsincronamenteSinTocarMetricasDeProduccion() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        seed(tenant, user);
        runner.register("modelo-a/prompt-v1", docs -> predictions(docs, 0.2, 5));
        send(aprobada(tenant, at(java.time.LocalDate.of(2026, 10, 3)), "AUTO_STP", "EC"));
        int dailyBefore = countRows("select count(*) from qa_metrics_daily where tenant_id = ?",
            UUID.fromString(tenant));

        var res = mvc.perform(post(BASE + "/evaluate").with(token(tenant, user))).andExpect(status().isAccepted())
            .andReturn();
        String jobId = body(res).get("job_id").asText();
        assertThat(UUID.fromString(jobId)).isNotNull();

        JsonNode done = awaitJob(tenant, user, jobId);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("model_prompt_key").asText()).isEqualTo("modelo-a/prompt-v1");
        assertThat(done.get("samples").asInt()).isEqualTo(80);
        // radicado: 8 de 10 correctos; monto: 1 de 5 incorrecto => 32 + 32 = 64 de 80.
        assertThat(done.get("accuracy").asDouble()).isEqualTo(0.8);
        assertThat(done.get("precision").asDouble()).isEqualTo(0.8);
        assertThat(done.get("recall").asDouble()).isEqualTo(0.8);
        assertThat(done.get("ece_calibrated").asDouble()).isLessThan(done.get("ece_raw").asDouble());
        assertThat(done.get("result").get("fields")).hasSize(2);

        assertThat(countRows("select count(*) from qa_metrics_daily where tenant_id = ?", UUID.fromString(tenant)))
            .isEqualTo(dailyBefore);
        assertThat(countRows("select count(*) from qa_calibration where tenant_id = ?", UUID.fromString(tenant)))
            .isEqualTo(1);
    }

    @Test
    void umbralesPorPrecisionObjetivoSeConsultanPorParModeloPrompt() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        seed(tenant, user);
        runner.register("modelo-a/prompt-v1", docs -> predictions(docs, 0.2, 5));
        String jobId = body(mvc.perform(post(BASE + "/evaluate").with(token(tenant, user))).andReturn())
            .get("job_id").asText();
        assertThat(awaitJob(tenant, user, jobId).get("status").asText()).isEqualTo("DONE");

        JsonNode t = body(mvc.perform(get("/v1/quality/calibration/thresholds?tipologia=EC")
            .with(token(tenant, user))).andExpect(status().isOk()).andReturn());
        assertThat(t.get("model_prompt_key").asText()).isEqualTo("modelo-a/prompt-v1");
        JsonNode radicado = null;
        JsonNode monto = null;
        for (JsonNode row : t.get("data")) {
            if ("radicado".equals(row.get("campo").asText())) {
                radicado = row;
            } else {
                monto = row;
            }
        }
        // radicado se separa por confianza: se puede auto-aprobar con precision 100 %.
        assertThat(radicado.get("attainable").asBoolean()).isTrue();
        assertThat(radicado.get("tau_auto").asDouble()).isGreaterThan(0.5);
        assertThat(radicado.get("scope").asText()).isEqualTo("FIELD");
        // monto es sobreconfiado y solo acierta 80 %: no alcanza el objetivo 0,9, nunca se auto-aprueba.
        assertThat(monto.get("attainable").asBoolean()).isFalse();
        assertThat(monto.get("tau_auto").asDouble()).isGreaterThan(1.0);

        mvc.perform(get("/v1/quality/calibration/thresholds?model_prompt_key=otro/par")
            .with(token(tenant, user))).andExpect(status().isOk());
    }

    @Test
    void evaluateSinResultadosRegistradosTerminaFallida() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        create(tenant, user, "uno", "{\"radicado\":\"R1\"}");
        String jobId = body(mvc.perform(post(BASE + "/evaluate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"model_prompt_key\":\"sin/registro\"}").with(token(tenant, user))).andReturn())
            .get("job_id").asText();
        JsonNode s = awaitJob(tenant, user, jobId);
        assertThat(s.get("status").asText()).isEqualTo("FAILED");
        assertThat(s.get("error").asText()).contains("Sin resultados");
    }

    @Test
    void evaluateConGoldenSetVacioTerminaFallida() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        String jobId = body(mvc.perform(post(BASE + "/evaluate").with(token(tenant, user))).andReturn())
            .get("job_id").asText();
        assertThat(awaitJob(tenant, user, jobId).get("error").asText()).contains("vacio");
    }

    @Test
    void evaluateRechazaClaveInvalida() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        mvc.perform(post(BASE + "/evaluate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"model_prompt_key\":\"../../etc/passwd\"}").with(token(tenant, user)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void replayComparaCandidatoContraVigenteEnParaleloSinTocarProduccion() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        seed(tenant, user);
        runner.register("modelo-a/prompt-v1", docs -> predictions(docs, 0.2, 5));
        runner.register("modelo-b/prompt-v2", docs -> predictions(docs, 0.0, 1000));
        UUID t = UUID.fromString(tenant);
        int metricsBefore = countRows("select count(*) from qa_metrics_daily where tenant_id = ?", t);

        String jobId = body(mvc.perform(post(BASE + "/replay").contentType(MediaType.APPLICATION_JSON)
            .content("{\"candidate_model_prompt_key\":\"modelo-b/prompt-v2\"}").with(token(tenant, user)))
            .andExpect(status().isAccepted()).andReturn()).get("job_id").asText();
        JsonNode s = awaitJob(tenant, user, jobId);

        assertThat(s.get("kind").asText()).isEqualTo("REPLAY");
        assertThat(s.get("status").asText()).isEqualTo("DONE");
        JsonNode report = s.get("result");
        assertThat(report.get("regression").asBoolean()).isFalse();
        assertThat(report.get("deltaF1").asDouble()).isGreaterThan(0.1);
        assertThat(report.get("candidate").get("modelPromptKey").asText()).isEqualTo("modelo-b/prompt-v2");
        assertThat(countRows("select count(*) from qa_metrics_daily where tenant_id = ?", t)).isEqualTo(metricsBefore);
        assertThat(countRows("select count(*) from qa_threshold where tenant_id = ?", t)).isZero();
    }

    @Test
    void importaVerdadTerrenoDelGeneradorSintetico() throws Exception {
        Files.writeString(GOLDEN_DIR.resolve("ec-0001.json"), "{\"id\":\"ec-0001\",\"tipologia\":\"EC\","
            + "\"sintetico\":true,\"tags\":[\"ruido\"],\"verdad\":{\"radicado\":\"11001202300000000000001\","
            + "\"monto\":500000}}");
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        try {
            mvc.perform(post(BASE + "/import").with(token(tenant, user))).andExpect(status().isOk());
            // Reimportar es idempotente por id externo.
            mvc.perform(post(BASE + "/import").with(token(tenant, user))).andExpect(status().isOk());
            JsonNode list = body(mvc.perform(get(BASE).with(token(tenant, user))).andReturn());
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("tags").get(0).asText()).isEqualTo("ruido");

            Files.writeString(GOLDEN_DIR.resolve("real.json"), "{\"id\":\"real-1\",\"tipologia\":\"EC\","
                + "\"verdad\":{\"radicado\":\"x\"}}");
            mvc.perform(post(BASE + "/import").with(token(tenant, user))).andExpect(status().isBadRequest());
        } finally {
            Files.deleteIfExists(GOLDEN_DIR.resolve("real.json"));
            Files.deleteIfExists(GOLDEN_DIR.resolve("ec-0001.json"));
        }
    }
}
