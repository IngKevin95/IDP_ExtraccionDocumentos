package com.idp.audit.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.audit.application.WormAnchorService;
import com.idp.audit.support.AuditTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** AC-08: legal hold bloquea la purga, extiende la proteccion WORM y emite eventos sin PII. */
class LegalHoldApiTest extends AuditTestSupport {
    @Autowired WormAnchorService anchors;

    private JsonNode hold(UUID t, String body) throws Exception {
        return json(mvc.perform(post("/v1/audit/legal-hold").with(token(t, "aud1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String apply(UUID doc) {
        return "{\"action\":\"APPLY\"," + (doc == null ? "" : "\"documentId\":\"" + doc + "\",")
                + "\"reason\":\"Requerimiento judicial Ref-2026-894\"}";
    }

    private String release(UUID doc) {
        return "{\"action\":\"RELEASE\"," + (doc == null ? "" : "\"documentId\":\"" + doc + "\",")
                + "\"reason\":\"Fin del proceso\"}";
    }

    private int purgeCheckStatus(UUID t, UUID doc) throws Exception {
        return mvc.perform(post("/v1/audit/retention/purge-check").with(token(t, "aud1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + (doc == null ? "" : "\"documentId\":\"" + doc + "\",") + "\"reason\":\"habeas data\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void ac08_holdDeTenantBloqueaLaPurgaYQuedaElRechazoEncadenado() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        grantAuditor(t, "aud1");
        consume(recibida(t, doc));
        anchors.anchorNow(t);
        String anchorPath = store.puts.stream().filter(p -> p.tenant().equals(t.toString())).findFirst()
                .orElseThrow().path();

        JsonNode r = hold(t, apply(null));
        assertEquals("ACTIVE", r.get("status").asText());
        assertEquals(t.toString(), r.get("tenantId").asText(), "tenant tomado del JWT");
        assertEquals("aud1", r.get("appliedBy").asText());
        assertTrue(r.get("documentId") == null || r.get("documentId").isNull());
        assertTrue(store.hasLegalHold(t.toString(), anchorPath), "proteccion WORM extendida al ancla");

        var events = publisher.of(t, "legalhold.aplicado");
        assertEquals(1, events.size());
        assertEquals(r.get("holdId").asText(), events.get(0).payload().get("holdId").asText());
        assertEquals("aud1", events.get(0).payload().get("appliedBy").asText());

        assertEquals(409, purgeCheckStatus(t, doc));
        assertEquals(409, purgeCheckStatus(t, null), "tambien la destruccion de KEK del tenant");
        assertEquals(2, jdbc.sql("select count(*) from audit_entries where tenant_id = :t "
                        + "and event_type = 'legalhold.purga_rechazada'").param("t", t).query(Long.class).single());
        mvc.perform(post("/v1/audit/retention/purge-check").with(token(t, "aud1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"" + doc + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEGAL_HOLD_ACTIVE"));
    }

    @Test
    void ac08_holdPorDocumentoSoloBloqueaEseDocumentoYEsIdempotente() throws Exception {
        UUID t = newTenant();
        UUID held = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        grantAuditor(t, "aud1");

        JsonNode first = hold(t, apply(held));
        JsonNode again = hold(t, apply(held));
        assertEquals(first.get("holdId").asText(), again.get("holdId").asText());
        assertEquals(held.toString(), first.get("documentId").asText());
        assertEquals(1, publisher.of(t, "legalhold.aplicado").size());

        assertEquals(409, purgeCheckStatus(t, held));
        assertEquals(200, purgeCheckStatus(t, free));
    }

    @Test
    void ac08_liberarPermiteLaPurgaRetiraElHoldWormYEmiteLiberado() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        grantAuditor(t, "aud1");
        consume(recibida(t, doc));
        anchors.anchorNow(t);
        String anchorPath = store.puts.stream().filter(p -> p.tenant().equals(t.toString())).findFirst()
                .orElseThrow().path();
        hold(t, apply(doc));
        assertTrue(store.hasLegalHold(t.toString(), anchorPath));

        JsonNode r = hold(t, release(doc));

        assertEquals("RELEASED", r.get("status").asText());
        assertFalse(store.hasLegalHold(t.toString(), anchorPath));
        var events = publisher.of(t, "legalhold.liberado");
        assertEquals(1, events.size());
        assertEquals("aud1", events.get(0).payload().get("releasedBy").asText());
        assertEquals(200, purgeCheckStatus(t, doc));
        mvc.perform(post("/v1/audit/legal-hold").with(token(t, "aud1")).contentType(MediaType.APPLICATION_JSON)
                .content(release(doc))).andExpect(status().isNotFound());
    }

    @Test
    void ac08_unHoldTenantVigenteExtiendeLaProteccionALosAnclajesNuevos() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        hold(t, apply(null));
        consume(aprobada(t, UUID.randomUUID()));
        var anchor = anchors.anchorNow(t).orElseThrow();
        assertTrue(store.hasLegalHold(t.toString(), anchor.fileUri()));
    }

    @Test
    void ac08_holdDeUnDocumentoNoProtegeAnclajesDeOtrosDocumentos() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        hold(t, apply(UUID.randomUUID()));
        consume(aprobada(t, UUID.randomUUID()));
        var anchor = anchors.anchorNow(t).orElseThrow();
        assertFalse(store.hasLegalHold(t.toString(), anchor.fileUri()));
    }

    @Test
    void ac08_peticionInvalidaYAliasDeRutaDeLaSpec() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        mvc.perform(post("/v1/audit/legal-hold").with(token(t, "aud1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPLY\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/audit/legal-hold").with(token(t, "aud1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"OTRA\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/audit/legal-holds").with(token(t, "aud1")).contentType(MediaType.APPLICATION_JSON)
                .content(apply(null))).andExpect(status().isOk());
        mvc.perform(post("/v1/audit/legal-hold").contentType(MediaType.APPLICATION_JSON).content(apply(null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/audit/legal-hold").with(token(t, "sinrol")).contentType(MediaType.APPLICATION_JSON)
                .content(apply(null))).andExpect(status().isForbidden());
    }
}
