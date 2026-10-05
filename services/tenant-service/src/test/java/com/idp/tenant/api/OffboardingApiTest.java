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
    @Autowired com.idp.tenant.infrastructure.persistence.ApprovalRepository approvalsRepo;
    @Autowired com.idp.tenant.infrastructure.persistence.LegalHoldRepository holdsRepo;

    private void legalHold(UUID id, boolean active) throws Exception {
        String body = "{\"active\":" + active + (active ? ",\"reasonCode\":\"LITIGATION\"" : "") + "}";
        if (!active) {
            // A3: la liberacion exige un segundo PLATFORM_ADMIN distinto
            mvc.perform(post("/v1/admin/tenants/" + id + "/legal-hold").with(platformAdmin("admin1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post("/v1/admin/tenants/" + id + "/legal-hold").with(platformAdmin(active ? "admin1" : "admin2"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
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

        // A3: la primera llamada solo registra la solicitud; la de un segundo administrador distinto la aprueba
        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertTrue(events(id, "tenant.baja_iniciada").isEmpty());
        mvc.perform(delete("/v1/admin/tenants/" + id).with(platformAdmin("admin2")))
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
        approveDelete(id);

        // plazo aun no cumplido: no se destruye nada
        offboarding.shredDueTenants();
        assertFalse(keys.disabled.contains("t-" + id + "-data"));
        assertEquals(TenantStatus.PENDING_DELETION, tenants.find(id).orElseThrow().status());

        makeOverdue(id);
        offboarding.shredDueTenants();

        assertTrue(keys.disabled.contains("t-" + id + "-data"));
        assertFalse(keys.disabled.contains("t-" + id + "-audit"));
        assertEquals(TenantStatus.DELETED, tenants.find(id).orElseThrow().status());
    }

    @Test
    void sec017_shreddingSeOmiteSiHayLegalHoldAplicadoDuranteLaEspera() throws Exception {
        UUID id = createTenant();
        approveDelete(id);
        legalHold(id, true);
        makeOverdue(id);

        offboarding.shredDueTenants();

        assertFalse(keys.disabled.contains("t-" + id + "-data"));
        assertEquals(TenantStatus.PENDING_DELETION, tenants.find(id).orElseThrow().status());

        legalHold(id, false);
        offboarding.shredDueTenants();
        assertTrue(keys.disabled.contains("t-" + id + "-data"));
    }

    @Test
    void k2_shreddingSeOmiteSiHayLegalHoldDeUnDocumento() throws Exception {
        UUID id = createTenant();
        approveDelete(id);
        UUID holdId = UUID.randomUUID();
        jdbc.sql("INSERT INTO legal_hold_records(id, tenant_id, document_id, reason, applied_by, status, created_at) "
                + "VALUES (:id, :t, :d, 'LITIGATION', 'cumplimiento', 'ACTIVE', :c)")
                .param("id", holdId).param("t", id).param("d", UUID.randomUUID())
                .param("c", OffsetDateTime.now(ZoneOffset.UTC)).update();
        makeOverdue(id);

        offboarding.shredDueTenants();

        assertFalse(keys.disabled.contains("t-" + id + "-data"));
        assertEquals(TenantStatus.PENDING_DELETION, tenants.find(id).orElseThrow().status());

        jdbc.sql("UPDATE legal_hold_records SET status = 'RELEASED' WHERE id = :id").param("id", holdId).update();
        offboarding.shredDueTenants();
        assertTrue(keys.disabled.contains("t-" + id + "-data"));
    }

    @Test
    void k2_errorTerminalDeShreddingNoSeReintentaEnBucle() throws Exception {
        UUID id = createTenant();
        approveDelete(id);
        makeOverdue(id);
        keys.disableFailure = new IllegalArgumentException("kek invalida");
        try {
            assertEquals(0, offboarding.shredDueTenants());
        } finally {
            keys.disableFailure = null;
        }

        assertEquals(TenantStatus.FAILED, tenants.find(id).orElseThrow().status());
        assertEquals(0, offboarding.shredDueTenants());
        assertFalse(keys.disabled.contains("t-" + id + "-data"));
    }

    @Test
    void k4_kekInexistenteSeTrataComoExitoIdempotente() throws Exception {
        UUID id = createTenant();
        approveDelete(id);
        makeOverdue(id);
        keys.disableFailure = new com.idp.kms.KeyService.KeyNotFoundException("kek ya destruida");
        try {
            assertEquals(1, offboarding.shredDueTenants());
        } finally {
            keys.disableFailure = null;
        }
        assertEquals(TenantStatus.DELETED, tenants.find(id).orElseThrow().status());
        assertEquals(0, offboarding.shredDueTenants());
    }

    @Test
    void k4_aprobacionYaResueltaDevuelveConflicto() throws Exception {
        UUID tenant = createTenant();
        approvalsRepo.insert(tenant, "DELETE_TENANT", tenant.toString(), "admin1", java.time.Instant.now());
        UUID reqId = approvalsRepo.findPending(tenant, "DELETE_TENANT", tenant.toString(),
                java.time.Instant.now().minusSeconds(60)).orElseThrow().id();
        assertTrue(approvalsRepo.approve(reqId, "admin2", java.time.Instant.now()));
        assertFalse(approvalsRepo.approve(reqId, "admin3", java.time.Instant.now()));
    }

    @Test
    void k4_servicioDeAprobacionLanzaConflictoSiLaSolicitudSeResolvioEnCarrera() {
        var repo = org.mockito.Mockito.mock(com.idp.tenant.infrastructure.persistence.ApprovalRepository.class);
        UUID req = UUID.randomUUID();
        org.mockito.Mockito.when(repo.findPending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(
                        new com.idp.tenant.infrastructure.persistence.ApprovalRepository.Pending(req, "admin1")));
        org.mockito.Mockito.when(repo.approve(org.mockito.ArgumentMatchers.eq(req),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        var svc = new com.idp.tenant.application.ApprovalService(repo, java.time.Clock.systemUTC());
        org.junit.jupiter.api.Assertions.assertThrows(com.idp.tenant.domain.Exceptions.ConflictException.class,
                () -> svc.requestOrApprove(UUID.randomUUID(), "DELETE_TENANT", "t", "admin2"));
    }

    @Test
    void legalHold_aplicarYLiberarEmiteEventosValidos() throws Exception {
        UUID id = createTenant();
        legalHold(id, true);
        legalHold(id, true); // idempotente
        JsonNode applied = event(id, "legalhold.aplicado");
        assertEquals("LITIGATION", applied.get("reasonCode").asText());
        assertEquals("admin1", applied.get("appliedBy").asText());
        assertFalse(holdsRepo.findActive(id).isEmpty());

        legalHold(id, false);
        JsonNode released = event(id, "legalhold.liberado");
        assertEquals(applied.get("holdId").asText(), released.get("holdId").asText());
        assertTrue(holdsRepo.findActive(id).isEmpty());

        mvc.perform(get("/v1/admin/tenants/" + id).with(platformAdmin("admin1"))).andExpect(status().isOk());
    }
}
