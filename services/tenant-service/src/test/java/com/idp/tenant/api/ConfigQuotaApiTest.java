package com.idp.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import com.idp.tenant.support.ApiTestSupport;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ConfigQuotaApiTest extends ApiTestSupport {
    @Autowired TenantRepository tenants;

    private long limit(UUID id, String metric) {
        return jdbc.sql("SELECT limit_value FROM quota_period WHERE tenant_id = :t AND metric_name = :m")
                .param("t", id).param("m", metric).query(Long.class).single();
    }

    private void consume(UUID id, String metric, long value, int expected) throws Exception {
        mvc.perform(post("/v1/admin/tenants/" + id + "/consumption").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metricName\":\"" + metric + "\",\"value\":" + value + "}"))
                .andExpect(status().is(expected));
    }

    @Test
    void ac05_cambioDePlanAplicaLimitesYActualizaLaBase() throws Exception {
        UUID id = createTenant();
        assertEquals(10000, limit(id, "PAGES_RENDERED"));

        mvc.perform(put("/v1/admin/tenants/" + id + "/config").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + DEDICATED + "\",\"settings\":{\"locale\":\"es-CO\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(DEDICATED.toString()));

        assertEquals(DEDICATED, tenants.find(id).orElseThrow().planId());
        assertEquals(100000, limit(id, "PAGES_RENDERED"));
        assertEquals("es-CO", tenants.findConfig(id).orElseThrow().settings().get("locale"));
    }

    @Test
    void ac05_configDeTenantNoActivoOPlanInexistenteSeRechaza() throws Exception {
        port.failBucket = true;
        UUID failed = createTenant();
        mvc.perform(put("/v1/admin/tenants/" + failed + "/config").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + DEDICATED + "\"}"))
                .andExpect(status().isConflict());

        port.failBucket = false;
        UUID ok = createTenant();
        mvc.perform(put("/v1/admin/tenants/" + ok + "/config").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/admin/tenants/" + UUID.randomUUID() + "/config").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + DEDICATED + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ac06_alSuperarElOchentaPorCientoSeEmiteUmbralUnaSolaVez() throws Exception {
        UUID id = createTenant();
        // limite 10000: exactamente 80% no supera el umbral
        consume(id, "PAGES_RENDERED", 8000, 202);
        assertTrue(events(id, "cuota.umbral_alcanzado").isEmpty());

        consume(id, "PAGES_RENDERED", 1, 202);
        JsonNode ev = event(id, "cuota.umbral_alcanzado");
        assertEquals("PAGES_RENDERED", ev.get("metricName").asText());
        assertEquals(80, ev.get("thresholdPercentage").asInt());

        consume(id, "PAGES_RENDERED", 500, 202);
        assertEquals(1, events(id, "cuota.umbral_alcanzado").size());

        assertEquals(3, events(id, "consumo.registrado").size());
        for (String e : events(id, "consumo.registrado")) {
            assertValid("consumo.registrado", e);
        }
        long consumed = jdbc.sql("SELECT consumed FROM quota_period WHERE tenant_id = :t AND metric_name = :m "
                        + "AND period_start = :p")
                .param("t", id).param("m", "PAGES_RENDERED")
                .param("p", LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)).query(Long.class).single();
        assertEquals(8501, consumed);
    }

    @Test
    void consumoConMetricaOValorInvalidoDevuelve400() throws Exception {
        UUID id = createTenant();
        consume(id, "NO_EXISTE", 10, 400);
        consume(id, "PAGES_RENDERED", 0, 400);
        consume(id, "PAGES_RENDERED", 10, 202);
    }
}
