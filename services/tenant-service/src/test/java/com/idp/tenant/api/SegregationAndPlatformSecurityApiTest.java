package com.idp.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.tenant.support.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A3 (PLATFORM_ADMIN revalidado, emisor de plataforma, doble aprobacion) y A4 (SoD en roles sensibles). */
class SegregationAndPlatformSecurityApiTest extends ApiTestSupport {

    private static String body(String user, String role) {
        return "{\"userId\":\"" + user + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void a3_claimDePlatformAdminSinFilaEnPlatformAdminSeDeniega() throws Exception {
        mvc.perform(get("/v1/admin/tenants").with(platformAdmin("intruso"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/tenants").with(platformAdmin("admin1"))).andExpect(status().isOk());
        jdbc.sql("UPDATE platform_admin SET disabled_at = CURRENT_TIMESTAMP WHERE subject = 'admin2'").update();
        try {
            mvc.perform(get("/v1/admin/tenants").with(platformAdmin("admin2"))).andExpect(status().isForbidden());
        } finally {
            jdbc.sql("UPDATE platform_admin SET disabled_at = NULL WHERE subject = 'admin2'").update();
        }
    }

    @Test
    void a3_tokenDeEmisorDeTenantNoAdministraLaPlataformaNiViceversa() throws Exception {
        mvc.perform(get("/v1/admin/tenants").with(jwt().jwt(j -> j.subject("admin1").issuer("https://idp.test/tenants"))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/tenants").with(jwt().jwt(j -> j.subject("admin1"))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isForbidden());
        UUID t = createTenantWithAdmin("boss");
        mvc.perform(get("/v1/tenant/users").with(jwt().jwt(j -> j.subject("boss").issuer(PLATFORM_ISSUER)
                .claim("tenant_id", t.toString())))).andExpect(status().isForbidden());
    }

    @Test
    void a3_bajaDeTenantYLiberacionDeLegalHoldRequierenUnSegundoAdminDistinto() throws Exception {
        UUID t = createTenant();
        mvc.perform(post("/v1/admin/tenants/" + t + "/legal-hold").with(platformAdmin("admin1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true,\"reasonCode\":\"LITIGATION\"}"))
                .andExpect(status().isOk());
        String release = "{\"active\":false}";
        // el mismo admin no puede aprobar su propia solicitud
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/v1/admin/tenants/" + t + "/legal-hold").with(platformAdmin("admin1"))
                    .contentType(MediaType.APPLICATION_JSON).content(release))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.active").value(true));
        }
        assertTrue(events(t, "legalhold.liberado").isEmpty());
        mvc.perform(post("/v1/admin/tenants/" + t + "/legal-hold").with(platformAdmin("admin2"))
                .contentType(MediaType.APPLICATION_JSON).content(release))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        event(t, "legalhold.liberado");

        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/v1/admin/tenants/" + t).with(platformAdmin("admin1")))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACTIVE"));
        }
        assertTrue(events(t, "tenant.baja_iniciada").isEmpty());
        mvc.perform(delete("/v1/admin/tenants/" + t).with(platformAdmin("admin2")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_DELETION"));
        event(t, "tenant.baja_iniciada");
        assertEquals("admin1", jdbc.sql("SELECT requested_by FROM approval_request WHERE tenant_id = :t "
                + "AND action = 'DELETE_TENANT'").param("t", t).query(String.class).single());
        assertEquals("admin2", jdbc.sql("SELECT approved_by FROM approval_request WHERE tenant_id = :t "
                + "AND action = 'DELETE_TENANT'").param("t", t).query(String.class).single());
    }

    @Test
    void a4_nadieSeAutoasignaUnRolSensible() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        for (String role : new String[] {"DATA_STEWARD", "AUDITOR", "OFICIAL_SEGURIDAD", "COMPLIANCE",
                "RIESGO_MODELO"}) {
            mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                    .content(body("boss", role))).andExpect(status().isForbidden());
        }
        assertFalse(roles.hasActiveRole(t, "boss", "AUDITOR", java.time.Instant.now()));
    }

    @Test
    void a4_rolSensibleParaOtroExigeAprobadorDistintoYEmiteEventoDeAuditoria() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        insertRole(t, "boss2", "TENANT_ADMIN", null);

        // primera llamada: solo solicitud; repetir con el mismo actor no aprueba
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                    .content(body("ana", "AUDITOR")))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        }
        assertFalse(roles.hasActiveRole(t, "ana", "AUDITOR", java.time.Instant.now()));
        assertTrue(events(t, "acceso.rol_sensible_otorgado").isEmpty());

        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss2")).contentType(MediaType.APPLICATION_JSON)
                .content(body("ana", "AUDITOR")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.approvedBy").value("boss2"))
                .andExpect(jsonPath("$.grantedBy").value("boss"));
        assertTrue(roles.hasActiveRole(t, "ana", "AUDITOR", java.time.Instant.now()));
        JsonNode ev = event(t, "acceso.rol_sensible_otorgado");
        assertEquals("ana", ev.get("subjectId").asText());
        assertEquals("boss", ev.get("requestedBy").asText());
        assertEquals("boss2", ev.get("approvedBy").asText());

        // roles no sensibles siguen siendo directos
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content(body("luis", "REVISOR"))).andExpect(status().isCreated());
    }
}
