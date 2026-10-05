package com.idp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.quality.metrics.DriftService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** quality-service spec: AC-06, AC-07, AC-08 y aislamiento por tenant en la API de reportes. */
class QualityApiTest extends AbstractQualityTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    @Autowired DriftService drift;

    private JsonNode stp(String tenant, String user, LocalDate from, LocalDate to, String extra) throws Exception {
        return body(mvc.perform(get("/v1/quality/reports/stp?start_date=" + from + "&end_date=" + to + extra)
            .with(token(tenant, user))).andExpect(status().isOk()).andReturn());
    }

    @Test
    void ac06_dataStewardRecibeLaSerieDeStpCalculada() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        for (int i = 0; i < 8; i++) {
            send(aprobada(tenant, at(D1), "AUTO_STP", "EC"));
        }
        send(aprobada(tenant, at(D1), "HUMAN_REVIEWER", "EC"));
        send(revision(tenant, at(D1), "EC", "RECHAZADO", false, "monto"));
        send(aprobada(tenant, at(D1.plusDays(1)), "AUTO_STP", "DC"));

        JsonNode r = stp(tenant, user, D1, D1.plusDays(1), "");
        assertThat(r.get("tenant_id").asText()).isEqualTo(tenant);
        JsonNode day1 = r.get("data").get(0);
        assertThat(day1.get("date").asText()).isEqualTo(D1.toString());
        assertThat(day1.get("total_documents").asInt()).isEqualTo(10);
        assertThat(day1.get("stp_count").asInt()).isEqualTo(8);
        assertThat(day1.get("hitl_count").asInt()).isEqualTo(1);
        assertThat(day1.get("stp_percentage").asDouble()).isEqualTo(80.0);
        assertThat(r.get("data")).hasSize(2);

        JsonNode onlyDc = stp(tenant, user, D1, D1.plusDays(1), "&tipologia=DC");
        assertThat(onlyDc.get("data")).hasSize(1);
        assertThat(onlyDc.get("data").get(0).get("stp_percentage").asDouble()).isEqualTo(100.0);
    }

    @Test
    void ac07_operadorRecibe403() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String operador = "operador-" + UUID.randomUUID();
        roles.grant(tenant, operador, "OPERADOR");
        mvc.perform(get("/v1/quality/reports/stp?start_date=" + D1 + "&end_date=" + D1)
            .with(token(tenant, operador))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/quality/reports/silent-error?start_date=" + D1 + "&end_date=" + D1)
            .with(token(tenant, operador))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/quality/reports/golden-set").with(token(tenant, operador)))
            .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenRecibe401() throws Exception {
        mvc.perform(get("/v1/quality/reports/stp?start_date=" + D1 + "&end_date=" + D1))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rolDeOtroTenantNoAutoriza() throws Exception {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        String user = steward(tenantA);
        mvc.perform(get("/v1/quality/reports/stp?start_date=" + D1 + "&end_date=" + D1)
            .with(token(tenantB, user))).andExpect(status().isForbidden());
    }

    @Test
    void elTenantSaleDelTokenYNuncaSeVeLaInformacionDeOtro() throws Exception {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        String userB = steward(tenantB);
        send(aprobada(tenantA, at(D1), "AUTO_STP", "EC"));
        assertThat(stp(tenantB, userB, D1, D1, "").get("data")).isEmpty();
    }

    @Test
    void parametrosInvalidosRecibe400() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        mvc.perform(get("/v1/quality/reports/stp?start_date=" + D1.plusDays(5) + "&end_date=" + D1)
            .with(token(tenant, user))).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/quality/reports/stp?start_date=" + D1 + "&end_date=" + D1 + "&tipologia=XX")
            .with(token(tenant, user))).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/quality/reports/stp?start_date=hoy&end_date=" + D1).with(token(tenant, user)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ac08_errorSilenteSobreElCincoPorCientoMarcaDerivaEnElReporte() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        LocalDate bad = D1;
        LocalDate ok = D1.plusDays(1);
        // 6 muestras ciegas con 2 errores (33 %) sobre el minimo de muestra (5): deriva.
        for (int i = 0; i < 4; i++) {
            send(revision(tenant, at(bad), "EC", "APROBADO", true));
        }
        for (int i = 0; i < 2; i++) {
            send(revision(tenant, at(bad), "EC", "APROBADO", true, "monto"));
        }
        // 20 muestras con 1 error = 5,0 %: no supera el umbral.
        for (int i = 0; i < 19; i++) {
            send(revision(tenant, at(ok), "EC", "APROBADO", true));
        }
        send(revision(tenant, at(ok), "EC", "APROBADO", true, "monto"));

        JsonNode r = body(mvc.perform(get("/v1/quality/reports/silent-error?start_date=" + bad + "&end_date=" + ok)
            .with(token(tenant, user))).andExpect(status().isOk()).andReturn());
        assertThat(r.get("data").get(0).get("silent_error_percentage").asDouble()).isEqualTo(33.33);
        assertThat(r.get("data").get(0).get("drift_detected").asBoolean()).isTrue();
        assertThat(r.get("data").get(1).get("blind_samples_total").asInt()).isEqualTo(20);
        assertThat(r.get("data").get(1).get("silent_error_percentage").asDouble()).isEqualTo(5.0);
        assertThat(r.get("data").get(1).get("drift_detected").asBoolean()).isFalse();
    }

    @Test
    void ac08_caidaDeStpMayorADiezPuntosMarcaDerivaYElJobPersisteLaAlerta() {
        String tenant = UUID.randomUUID().toString();
        UUID t = UUID.fromString(tenant);
        LocalDate today = D1.plusDays(5);
        for (int d = 1; d <= 3; d++) { // historia: 90 % STP
            for (int i = 0; i < 9; i++) {
                send(aprobada(tenant, at(today.minusDays(d)), "AUTO_STP", "EC"));
            }
            send(aprobada(tenant, at(today.minusDays(d)), "HUMAN_REVIEWER", "EC"));
        }
        for (int i = 0; i < 5; i++) { // hoy: 50 % STP
            send(aprobada(tenant, at(today), "AUTO_STP", "EC"));
            send(aprobada(tenant, at(today), "HUMAN_REVIEWER", "EC"));
        }
        assertThat(drift.evaluate(t, today).stpDrop()).isTrue();
        assertThat(drift.evaluate(t, today.minusDays(1)).drift()).isFalse();

        assertThat(drift.evaluateAll(today)).isGreaterThanOrEqualTo(1);
        assertThat(countRows("select count(*) from qa_drift_alert where tenant_id = ? and fecha = ? "
            + "and kind = 'STP_DROP'", t, today)).isEqualTo(1);
    }

    @Test
    void p95DeLatenciaSoloSePublicaConLaMuestraMinima() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        for (int i = 1; i <= 4; i++) {
            send(event("extraccion.completada", tenant, UUID.randomUUID(), at(D1), "typology", "EC")
                .put("latencyMs", i * 100).put("costMicros", i * 10));
        }
        String url = "/v1/quality/reports/performance?start_date=" + D1 + "&end_date=" + D1;
        JsonNode few = body(mvc.perform(get(url).with(token(tenant, user))).andExpect(status().isOk()).andReturn());
        assertThat(few.get("sufficient").asBoolean()).isFalse();
        assertThat(few.has("latency_p95_ms")).isFalse();

        for (int i = 5; i <= 20; i++) {
            send(event("extraccion.completada", tenant, UUID.randomUUID(), at(D1), "typology", "EC")
                .put("latencyMs", i * 100).put("costMicros", i * 10));
        }
        JsonNode enough = body(mvc.perform(get(url).with(token(tenant, user))).andExpect(status().isOk())
            .andReturn());
        assertThat(enough.get("sufficient").asBoolean()).isTrue();
        assertThat(enough.get("latency_p95_ms").asLong()).isEqualTo(1900);
        assertThat(enough.get("cost_p95_micros").asLong()).isEqualTo(190);
    }

    @Test
    void tasaDeCorreccionHumanaPorCampo() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        send(revision(tenant, at(D1), "EC", "APROBADO", false, "monto"));
        send(revision(tenant, at(D1), "EC", "APROBADO", false, "monto", "radicado"));
        send(revision(tenant, at(D1), "EC", "APROBADO", false));
        send(revision(tenant, at(D1), "EC", "APROBADO", false));

        JsonNode r = body(mvc.perform(get("/v1/quality/reports/fields?start_date=" + D1 + "&end_date=" + D1)
            .with(token(tenant, user))).andExpect(status().isOk()).andReturn());
        JsonNode monto = r.get("data").get(0);
        assertThat(monto.get("campo").asText()).isEqualTo("monto");
        assertThat(monto.get("corrections").asInt()).isEqualTo(2);
        assertThat(monto.get("correction_rate").asDouble()).isEqualTo(0.5);
        assertThat(r.get("data").get(1).get("correction_rate").asDouble()).isEqualTo(0.25);
    }
}
