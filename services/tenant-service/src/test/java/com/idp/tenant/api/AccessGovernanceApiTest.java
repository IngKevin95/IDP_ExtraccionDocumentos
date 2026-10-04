package com.idp.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.tenant.TenantId;
import com.idp.tenant.application.BreakGlassService;
import com.jayway.jsonpath.JsonPath;
import com.idp.tenant.support.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AccessGovernanceApiTest extends ApiTestSupport {
    @Autowired BreakGlassService breakGlass;

    private String breakGlassBody(String user, String approver, int ttl) {
        return "{\"userId\":\"" + user + "\",\"approvedBy\":\"" + approver + "\",\"ttlMinutes\":" + ttl
                + ",\"justification\":\"Incidente P1 en extraccion\"}";
    }

    @Test
    void ac07_breakGlassAprobadoPorOtroAdminCreaAsignacionConTtlYEmiteEvento() throws Exception {
        UUID t = createTenant();
        Instant before = Instant.now();

        mvc.perform(post("/v1/admin/tenants/" + t + "/break-glass").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content(breakGlassBody("sre1", "admin2", 60)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("BREAK_GLASS"))
                .andExpect(jsonPath("$.approvedBy").value("admin2"));

        var active = roles.findActiveByUser(t, "sre1", Instant.now());
        assertEquals(1, active.size());
        assertEquals("BREAK_GLASS", active.get(0).role());
        assertTrue(active.get(0).expiresAt().isAfter(before.plusSeconds(59 * 60)));
        assertTrue(active.get(0).expiresAt().isBefore(before.plusSeconds(61 * 60)));

        JsonNode ev = event(t, "breakglass.otorgado");
        assertEquals("sre1", ev.get("subjectId").asText());
        assertEquals("admin2", ev.get("approvedBy").asText());
    }

    @Test
    void ac07_breakGlassExigeAprobadorDistintoJustificacionYTtlAcotado() throws Exception {
        UUID t = createTenant();
        mvc.perform(post("/v1/admin/tenants/" + t + "/break-glass").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content(breakGlassBody("sre1", "admin1", 60)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/admin/tenants/" + t + "/break-glass").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content(breakGlassBody("sre1", "admin2", 100000)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/tenants/" + t + "/break-glass").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"sre1\",\"approvedBy\":\"admin2\",\"ttlMinutes\":10,\"justification\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/tenants/" + t + "/break-glass").with(tenantUser(t, "boss"))
                        .contentType(MediaType.APPLICATION_JSON).content(breakGlassBody("sre1", "admin2", 60)))
                .andExpect(status().isForbidden());
        assertTrue(events(t, "breakglass.otorgado").isEmpty());
    }

    @Test
    void breakGlassVencidoSeCierraYEmiteBreakglassExpirado() throws Exception {
        UUID t = createTenant();
        insertRole(t, "sre2", "BREAK_GLASS", Instant.now().minusSeconds(60));

        assertEquals(1, breakGlass.expireDue());

        assertFalse(roles.hasActiveRole(t, "sre2", "BREAK_GLASS", Instant.now()));
        assertEquals("sre2", event(t, "breakglass.expirado").get("subjectId").asText());
        assertEquals(0, breakGlass.expireDue());
    }

    @Test
    void ac09_certificacionDeAccesosGeneraReporteFirmadoVerificable() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"ana\",\"role\":\"REVISOR\"}")).andExpect(status().isCreated());
        mvc.perform(delete("/v1/tenant/users/ana").with(tenantUser(t, "boss"))).andExpect(status().isNoContent());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int quarter = (now.getMonthValue() - 1) / 3 + 1;
        String res = mvc.perform(post("/v1/admin/tenants/" + t + "/access-certifications")
                        .with(platformAdmin("admin1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":" + now.getYear() + ",\"quarter\":" + quarter + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyId").value("access-certification"))
                .andReturn().getResponse().getContentAsString();

        String reportJson = JsonPath.read(res, "$.reportJson");
        String signature = JsonPath.read(res, "$.signature");
        UUID certId = UUID.fromString(JsonPath.read(res, "$.id"));

        JsonNode report = JSON.readTree(reportJson);
        assertEquals(t.toString(), report.get("tenantId").asText());
        assertEquals(now.getYear(), report.get("period").get("year").asInt());
        assertEquals(2, report.get("assignments").size());
        assertEquals("ana", report.get("assignments").get(0).get("userId").asText());
        assertFalse(report.get("assignments").get(0).get("revokedAt").isNull());
        assertEquals("boss", report.get("assignments").get(1).get("userId").asText());
        assertTrue(report.get("assignments").get(1).get("revokedAt").isNull());

        // la firma se verifica con el puerto KeyService y falla si el reporte se altera
        byte[] sig = Base64.getDecoder().decode(signature);
        TenantId tid = new TenantId(t.toString());
        assertTrue(keys.verify(tid, reportJson.getBytes(StandardCharsets.UTF_8), sig, "access-certification"));
        assertFalse(keys.verify(tid, reportJson.replace("boss", "evil").getBytes(StandardCharsets.UTF_8), sig,
                "access-certification"));

        mvc.perform(get("/v1/admin/tenants/" + t + "/access-certifications/" + certId + "/verification")
                        .with(platformAdmin("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(get("/v1/admin/tenants/" + t + "/access-certifications/" + certId)
                        .with(platformAdmin("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signature").value(signature));
        mvc.perform(get("/v1/admin/tenants/" + UUID.randomUUID() + "/access-certifications/" + certId)
                        .with(platformAdmin("admin1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void ac09_certificacionRechazaPeriodosInvalidosOFuturosYExigeRolDePlataforma() throws Exception {
        UUID t = createTenant();
        int nextYear = OffsetDateTime.now(ZoneOffset.UTC).getYear() + 1;
        mvc.perform(post("/v1/admin/tenants/" + t + "/access-certifications").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":" + nextYear + ",\"quarter\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/tenants/" + t + "/access-certifications").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"quarter\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/tenants/" + t + "/access-certifications").with(tenantUser(t, "boss"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"quarter\":1}"))
                .andExpect(status().isForbidden());
    }
}
