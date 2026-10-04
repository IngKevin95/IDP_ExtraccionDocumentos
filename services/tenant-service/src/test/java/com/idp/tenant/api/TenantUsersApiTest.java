package com.idp.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/** Usuarios y roles del tenant (T-09, SEC-002): tenant del token, revalidacion por request, acceso.revocado. */
class TenantUsersApiTest extends ApiTestSupport {

    private String assign(String user, String role) {
        return "{\"userId\":\"" + user + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void administradorDelTenantListaAsignaYRevocaUsuarios() throws Exception {
        UUID t = createTenantWithAdmin("boss");

        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                        .content(assign("ana", "REVISOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("REVISOR"));
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(delete("/v1/tenant/users/ana").with(tenantUser(t, "boss"))).andExpect(status().isNoContent());
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "boss")))
                .andExpect(jsonPath("$.length()").value(1));

        JsonNode ev = event(t, "acceso.revocado");
        assertEquals("ana", ev.get("subjectId").asText());
        // borrado logico: la fila sigue existiendo
        Integer rows = jdbc.sql("SELECT COUNT(*) FROM role_assignment WHERE tenant_id = :t AND user_id = 'ana' "
                + "AND deleted_at IS NOT NULL").param("t", t).query(Integer.class).single();
        assertEquals(1, rows);
    }

    @Test
    void revocacionSurteEfectoEnElSiguienteRequestSinEsperarLaCache() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content(assign("bob", "TENANT_ADMIN"))).andExpect(status().isCreated());
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "bob"))).andExpect(status().isOk());

        mvc.perform(delete("/v1/tenant/users/bob").with(tenantUser(t, "boss"))).andExpect(status().isNoContent());

        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "bob"))).andExpect(status().isForbidden());
    }

    @Test
    void elJwtSoloProbaIdentidadSeRevalidaRolTenantYEstado() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        UUID other = createTenantWithAdmin("otro");

        mvc.perform(get("/v1/tenant/users")).andExpect(status().isUnauthorized());
        // usuario sin rol en el tenant del token
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "intruso"))).andExpect(status().isForbidden());
        // administrador de otro tenant no entra al tenant t: el tenant sale del token, nunca del path
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "otro"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/tenant/users").with(tenantUser(other, "otro"))).andExpect(status().isOk());
        // token sin tenant_id o con tenant inexistente
        mvc.perform(get("/v1/tenant/users").with(platformAdmin("admin1"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/tenant/users").with(tenantUser(UUID.randomUUID(), "boss")))
                .andExpect(status().isForbidden());
        // tenant en baja
        mvc.perform(delete("/v1/admin/tenants/" + t).with(platformAdmin("admin1"))).andExpect(status().isAccepted());
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "boss"))).andExpect(status().isForbidden());
    }

    @Test
    void asignacionesInvalidasSeRechazan() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content(assign("ana", "BREAK_GLASS"))).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content(assign("ana", "INVENTADO"))).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"ana\",\"role\":\"AUDITOR\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content(assign("ana", "AUDITOR"))).andExpect(status().isCreated());
        mvc.perform(post("/v1/tenant/users").with(tenantUser(t, "boss")).contentType(MediaType.APPLICATION_JSON)
                .content(assign("ana", "AUDITOR"))).andExpect(status().isConflict());
        mvc.perform(delete("/v1/tenant/users/nadie").with(tenantUser(t, "boss"))).andExpect(status().isNotFound());
        mvc.perform(delete("/v1/tenant/users/boss").with(tenantUser(t, "boss"))).andExpect(status().isConflict());
        assertTrue(events(t, "acceso.revocado").isEmpty());
    }
}
