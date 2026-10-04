package com.idp.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.tenant.application.OffboardingService;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import com.idp.tenant.support.ApiTestSupport;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class OffboardingApiTest extends ApiTestSupport {
    @Autowired TenantRepository tenants;
    @Autowired OffboardingService offboarding;

    private void legalHold(UUID id, boolean active) throws Exception {
        mvc.perform(post("/v1/admin/tenants/" + id + "/legal-hold").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":" + active + (active ? ",\"reasonCode\":\"LITIGATION\"" : "") + "}"))
                .andExpect(status().isOk());
    }

    private void makeOverdue(UUID id) {
        jdbc.sql("UPDATE tenants SET deletion_due_at = :d WHERE id = :id")
                .param("d", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)).param("id", id).update();
    }

    @Test
    void ac03_legalHoldActivoImpideLaBajaConConflicto() throws Exception {
        UUID id = createTenant();
        legalHold(id, true);

        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1")))
                .andExpect(status().isConflict());

        assertEquals(TenantStatus.ACTIVE, tenants.find(id).orElseThrow().status());
        assertTrue(events(id, "tenant.baja_iniciada").isEmpty());
    }

    @Test
    void ac04_bajaExitosaMarcaPendingDeletionYEmiteEvento() throws Exception {
        UUID id = createTenant();

        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_DELETION"));

        var tenant = tenants.find(id).orElseThrow();
        assertEquals(TenantStatus.PENDING_DELETION, tenant.status());
        assertNotNull(tenant.deletionDueAt());
        event(id, "tenant.baja_iniciada");

        // idempotente: segunda llamada no duplica el evento
        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1")))
                .andExpect(status().isAccepted());
        assertEquals(1, events(id, "tenant.baja_iniciada").size());
    }

    @Test
    void bajaDeTenantInexistenteDevuelve404() throws Exception {
        mvc.perform(delete("/v1/admin/tenants/" + UUID.randomUUID()).with(platformAdmin("admin1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void ac08_shreddingDeshabilitaKekDeDatosYConservaLaDeAuditoria() throws Exception {
        UUID id = createTenant();
        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1"))).andExpect(status().isAccepted());

        // plazo aun no cumplido: no se destruye nada
        offboarding.shredDueTenants();
        assertFalse(keys.disabled.contains("tenants/" + id + "/kek-data"));
        assertEquals(TenantStatus.PENDING_DELETION, tenants.find(id).orElseThrow().status());

        makeOverdue(id);
        offboarding.shredDueTenants();

        assertTrue(keys.disabled.contains("tenants/" + id + "/kek-data"));
        assertFalse(keys.disabled.contains("tenants/" + id + "/kek-audit"));
        assertEquals(TenantStatus.DELETED, tenants.find(id).orElseThrow().status());
    }

    @Test
    void sec017_shreddingSeOmiteSiHayLegalHoldAplicadoDuranteLaEspera() throws Exception {
        UUID id = createTenant();
        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1"))).andExpect(status().isAccepted());
        legalHold(id, true);
        makeOverdue(id);

        offboarding.shredDueTenants();

        assertFalse(keys.disabled.contains("tenants/" + id + "/kek-data"));
        assertEquals(TenantStatus.PENDING_DELETION, tenants.find(id).orElseThrow().status());

        legalHold(id, false);
        offboarding.shredDueTenants();
        assertTrue(keys.disabled.contains("tenants/" + id + "/kek-data"));
    }

    @Test
    void legalHold_aplicarYLiberarEmiteEventosValidos() throws Exception {
        UUID id = createTenant();
        legalHold(id, true);
        legalHold(id, true); // idempotente
        JsonNode applied = event(id, "legalhold.aplicado");
        assertEquals("LITIGATION", applied.get("reasonCode").asText());
        assertEquals("admin1", applied.get("appliedBy").asText());
        assertTrue(tenants.findConfig(id).orElseThrow().legalHold());

        legalHold(id, false);
        JsonNode released = event(id, "legalhold.liberado");
        assertEquals(applied.get("holdId").asText(), released.get("holdId").asText());
        assertFalse(tenants.findConfig(id).orElseThrow().legalHold());

        mvc.perform(get("/v1/admin/tenants/" + id).with(platformAdmin("admin1"))).andExpect(status().isOk());
    }
}
