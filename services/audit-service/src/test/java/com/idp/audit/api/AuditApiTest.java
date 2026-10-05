package com.idp.audit.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.application.CanonicalJson;
import com.idp.audit.application.WormAnchorService;
import com.idp.audit.support.AuditTestSupport;
import com.idp.tenant.TenantId;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** AC-02, AC-04, AC-07 y seguridad de la API (JWT de prueba, tenant del token, revalidacion de rol). */
class AuditApiTest extends AuditTestSupport {
    @Autowired WormAnchorService anchors;

    private String dossier(UUID tenant, String user, UUID doc) throws Exception {
        return mvc.perform(get("/v1/audit/dossiers/" + doc).with(token(tenant, user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode publicVerify(String body) throws Exception {
        return json(mvc.perform(post("/v1/audit/public/verify-signature").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void ac04_expedienteConsolidaLaTrazaDelDocumentoYEstaFirmadoConEd25519() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        grantAuditor(t, "aud1");
        consume(recibida(t, doc));
        consume(aprobada(t, UUID.randomUUID()));
        consume(aprobada(t, doc));
        anchors.anchorNow(t);

        String body = dossier(t, "aud1", doc);
        JsonNode d = json(body);

        assertEquals(t.toString(), d.get("tenantId").asText());
        assertEquals(doc.toString(), d.get("documentId").asText());
        assertEquals(2, d.get("totalEvents").asInt());
        assertEquals("documento.recibido", d.get("events").get(0).get("eventType").asText());
        assertEquals("extraccion.aprobada", d.get("events").get(1).get("eventType").asText());
        assertEquals(1, d.get("events").get(0).get("sequenceId").asLong());
        assertEquals(3, d.get("events").get(1).get("sequenceId").asLong());
        assertEquals(1, d.get("anchors").size(), "ruta del hash hacia el ancla WORM");
        assertEquals("ed25519", d.get("signature").get("algorithm").asText());
        assertEquals("audit-signing", d.get("signature").get("keyId").asText());

        ObjectNode unsigned = d.deepCopy();
        unsigned.remove("signature");
        byte[] sig = Base64.getDecoder().decode(d.get("signature").get("signatureValue").asText());
        assertTrue(keys.verify(new TenantId(t.toString()), CanonicalJson.bytes(unsigned), sig, "audit-signing"));
    }

    @Test
    void ac04_laVerificacionPublicaAceptaElExpedienteYRechazaCualquierAlteracion() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        grantAuditor(t, "aud1");
        consume(recibida(t, doc));
        consume(aprobada(t, doc));
        String body = dossier(t, "aud1", doc);

        JsonNode ok = publicVerify(body);
        assertTrue(ok.get("valid").asBoolean());
        assertTrue(ok.get("signatureValid").asBoolean());
        assertTrue(ok.get("eventHashesValid").asBoolean());

        ObjectNode alteredPayload = (ObjectNode) json(body);
        ((ObjectNode) alteredPayload.get("events").get(1).get("payload")).put("approvedBy", "AUTO_STP");
        JsonNode r1 = publicVerify(JSON.writeValueAsString(alteredPayload));
        assertFalse(r1.get("valid").asBoolean());
        assertFalse(r1.get("eventHashesValid").asBoolean());

        ObjectNode alteredMeta = (ObjectNode) json(body);
        alteredMeta.put("totalEvents", 9);
        JsonNode r2 = publicVerify(JSON.writeValueAsString(alteredMeta));
        assertFalse(r2.get("valid").asBoolean());
        assertFalse(r2.get("signatureValid").asBoolean());

        mvc.perform(post("/v1/audit/public/verify-signature").contentType(MediaType.APPLICATION_JSON)
                .content("no es json")).andExpect(status().isBadRequest());
    }

    @Test
    void ac04_elTenantSaleDelJwtNuncaDelPathYNoSeFiltranExpedientesAjenos() throws Exception {
        UUID a = newTenant();
        UUID b = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(a, doc));
        grantAuditor(b, "audB");

        mvc.perform(get("/v1/audit/dossiers/" + doc).with(token(b, "audB")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_NOT_FOUND"));
    }

    @Test
    void ac04_exigeJwtYRolAuditorVigenteRevalidadoEnCadaRequest() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(t, doc));
        String path = "/v1/audit/dossiers/" + doc;

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(token(t, "sinrol"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("x")))).andExpect(status().isForbidden());

        grantAuditor(t, "aud1");
        mvc.perform(get(path).with(token(t, "aud1"))).andExpect(status().isOk());

        jdbc.sql("delete from role_assignment where tenant_id = :t and user_id = 'aud1'").param("t", t).update();
        roleCache.invalidateUser(t.toString(), "aud1");
        mvc.perform(get(path).with(token(t, "aud1"))).andExpect(status().isForbidden());
    }

    @Test
    void ac07_expedienteDeDocumentoPurgadoOfuscaPayloadsPeroConservaLaCadenaVerificable() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        grantAuditor(t, "aud1");
        consume(recibida(t, doc));
        consume(aprobada(t, doc));
        consume(purgado(t, doc));

        String body = dossier(t, "aud1", doc);
        JsonNode d = json(body);

        assertTrue(d.get("purged").asBoolean());
        assertEquals(3, d.get("totalEvents").asInt());
        for (int i = 0; i < 2; i++) {
            JsonNode e = d.get("events").get(i);
            assertTrue(e.get("redacted").asBoolean());
            assertEquals("documento.purgado", e.get("payload").get("redacted").asText());
            assertFalse(e.get("payload").has("objectStoreKey"));
            assertEquals(64, e.get("payloadSha256").asText().length());
        }
        JsonNode purge = d.get("events").get(2);
        assertEquals("documento.purgado", purge.get("eventType").asText());
        assertFalse(purge.get("redacted").asBoolean());
        assertTrue(purge.get("payload").has("purgedAt"), "constancia del evento purgado");

        assertTrue(publicVerify(body).get("valid").asBoolean(), "la validez matematica de la cadena se conserva");
        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.isChainIntact").value(true));
    }

    @Test
    void ac02_verifyReportaCadenaIntegraYRespetaElRango() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        for (int i = 0; i < 4; i++) {
            consume(aprobada(t, UUID.randomUUID()));
        }
        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(t.toString()))
                .andExpect(jsonPath("$.isChainIntact").value(true))
                .andExpect(jsonPath("$.totalRecordsVerified").value(4))
                .andExpect(jsonPath("$.wormAnchorsVerified").value(0))
                .andExpect(jsonPath("$.errorsFound").isEmpty());
        mvc.perform(get("/v1/audit/verify?startSequenceId=2&endSequenceId=3").with(token(t, "aud1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRecordsVerified").value(2))
                .andExpect(jsonPath("$.isChainIntact").value(true));
        mvc.perform(get("/v1/audit/verify?startSequenceId=0").with(token(t, "aud1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ac02_verifyDetectaPayloadAlteradoYEmiteAlertaCritica() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        for (int i = 0; i < 3; i++) {
            consume(aprobada(t, UUID.randomUUID()));
        }
        jdbc.sql("update audit_entries set payload = CAST(:p AS JSONB) where tenant_id = :t and sequence_id = 2")
                .param("p", "{\"documentId\":\"" + UUID.randomUUID() + "\",\"approvedBy\":\"AUTO_STP\"}")
                .param("t", t).update();

        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.isChainIntact").value(false))
                .andExpect(jsonPath("$.errorsFound[0].sequenceId").value(2))
                .andExpect(jsonPath("$.errorsFound[0].errorType").value("HASH_MISMATCH"));
        var alerts = publisher.of(t, "auditoria.alerta_integridad");
        assertEquals(1, alerts.size());
        assertEquals(2, alerts.get(0).payload().get("sequenceId").asInt());
    }

    @Test
    void ac02_verifyDetectaHuecosDeSecuencia() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        for (int i = 0; i < 4; i++) {
            consume(aprobada(t, UUID.randomUUID()));
        }
        jdbc.sql("delete from audit_entries where tenant_id = :t and sequence_id = 3").param("t", t).update();

        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.isChainIntact").value(false))
                .andExpect(jsonPath("$.errorsFound[?(@.errorType=='SEQUENCE_GAP')].sequenceId").value(3));
    }

    @Test
    void ac02_verifyDetectaAnclajeWormAlteradoOFirmaInvalida() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        for (int i = 0; i < 3; i++) {
            consume(aprobada(t, UUID.randomUUID()));
        }
        var anchor = anchors.anchorNow(t).orElseThrow();
        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.isChainIntact").value(true))
                .andExpect(jsonPath("$.wormAnchorsVerified").value(1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write("{\"manifest\":{\"entries\":[]},\"manifestHash\":\"x\"}".getBytes());
        }
        store.tamper(t.toString(), anchor.fileUri(), out.toByteArray());

        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.isChainIntact").value(false))
                .andExpect(jsonPath("$.errorsFound[0].errorType").value("WORM_DISCREPANCY"));
    }

    @Test
    void ac02_verifyDetectaRegistroDistintoDeLoAnclado() throws Exception {
        UUID t = newTenant();
        grantAuditor(t, "aud1");
        for (int i = 0; i < 3; i++) {
            consume(aprobada(t, UUID.randomUUID()));
        }
        anchors.anchorNow(t);
        jdbc.sql("update audit_entries set current_hash = :h where tenant_id = :t and sequence_id = 3")
                .param("h", "e".repeat(64)).param("t", t).update();

        mvc.perform(get("/v1/audit/verify").with(token(t, "aud1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.isChainIntact").value(false))
                .andExpect(jsonPath("$.errorsFound[?(@.errorType=='WORM_DISCREPANCY')]").isNotEmpty())
                .andExpect(jsonPath("$.errorsFound[?(@.errorType=='HASH_MISMATCH')]").isNotEmpty());
    }

    @Test
    void ac02_elExpedienteDeUnaCadenaCorruptaSeRechazaConConflicto() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        grantAuditor(t, "aud1");
        consume(recibida(t, doc));
        jdbc.sql("update audit_entries set current_hash = :h where tenant_id = :t and sequence_id = 1")
                .param("h", "d".repeat(64)).param("t", t).update();

        mvc.perform(get("/v1/audit/dossiers/" + doc).with(token(t, "aud1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDIT_CHAIN_CORRUPT"));
        assertEquals(1, publisher.of(t, "auditoria.alerta_integridad").size());
    }
}
