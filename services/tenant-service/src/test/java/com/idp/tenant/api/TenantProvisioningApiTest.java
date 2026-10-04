package com.idp.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import com.idp.tenant.support.ApiTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class TenantProvisioningApiTest extends ApiTestSupport {
    @Autowired TenantRepository tenants;

    private String body(UUID plan) {
        return "{\"name\":\"Banco Demo\",\"planId\":\"" + plan + "\"}";
    }

    @Test
    void ac01_altaExitosaCreaTenantAprovisionaSiloYEmiteEvento() throws Exception {
        String res = mvc.perform(post("/v1/admin/tenants").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(STANDARD)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.planId").value(STANDARD.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JSON.readTree(res).get("id").asText());

        assertEquals(List.of("APPLY:database:OK", "APPLY:bucket:OK", "APPLY:keys:OK", "APPLY:openbao-role:OK"),
                tenants.findSteps(id));
        var config = tenants.findConfig(id).orElseThrow();
        assertEquals("tenants/" + id + "/kek-data", config.dataKekId());
        assertEquals("tenants/" + id + "/kek-audit", config.auditKekId());
        assertTrue(tenants.findSilo(id).isPresent());

        JsonNode ev = event(id, "tenant.aprovisionado");
        assertEquals(STANDARD.toString(), ev.get("planId").asText());
        assertEquals(id.toString(), ev.get("tenantId").asText());

        mvc.perform(get("/v1/admin/tenants/" + id).with(platformAdmin("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void ac02_falloDeBucketCompensaMarcaFailedYEmiteEventoFallido() throws Exception {
        port.failBucket = true;
        String res = mvc.perform(post("/v1/admin/tenants").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(STANDARD)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JSON.readTree(res).get("id").asText());

        assertEquals(List.of("APPLY:database:OK", "APPLY:bucket:FAILED", "COMPENSATE:database:OK"),
                tenants.findSteps(id));
        JsonNode ev = event(id, "tenant.aprovisionamiento_fallido");
        assertEquals("INFRASTRUCTURE_ERROR", ev.get("reasonCode").asText());
        assertTrue(events(id, "tenant.aprovisionado").isEmpty());
    }

    @Test
    void planInexistenteDevuelve400() throws Exception {
        mvc.perform(post("/v1/admin/tenants").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payloadInvalidoDevuelve400() throws Exception {
        mvc.perform(post("/v1/admin/tenants").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void administracionExigeRolDePlataforma() throws Exception {
        mvc.perform(post("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON).content(body(STANDARD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/admin/tenants").with(tenantUser(UUID.randomUUID(), "u1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(STANDARD)))
                .andExpect(status().isForbidden());
    }
}
