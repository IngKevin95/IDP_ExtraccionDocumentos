package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.security.Roles;
import com.idp.document.infra.PipelineEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class DocumentFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired PipelineEventListener listener;
    @Autowired MeterRegistry meters;

    private String operator(String tenant, String user) {
        roles.grant(tenant, user, Roles.OPERADOR);
        return user;
    }

    private String uploadOk(String tenant, String user) throws Exception {
        MvcResult r = upload(tenant, user, pdf());
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return body(r).path("id").asText();
    }

    @Test
    void ac01_mismoBinarioDevuelve200ConElIdOriginalSinNuevoProcesamiento() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        byte[] content = pdf();
        MvcResult first = upload(tenant, "ana", content, "a.pdf", radicado(), 1, null, "key-1");
        MvcResult second = upload(tenant, "ana", content, "b.pdf", radicado(), 1, null, "key-2");
        MvcResult sameKey = upload(tenant, "ana", content, "c.pdf", radicado(), 1, null, "key-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(first).path("status").asText()).isEqualTo("EN_EXTRACCION");
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(sameKey.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second).path("id").asText()).isEqualTo(body(first).path("id").asText());
        assertThat(body(sameKey).path("id").asText()).isEqualTo(body(first).path("id").asText());
        assertThat(renderer.calls.get()).isEqualTo(1);
        assertThat(outbox(tenant, "documento.recibido")).hasSize(1);
    }

    @Test
    void ac01_idempotencyKeyReusadaConOtroContenidoYTuplaDeNegocioDuplicadaDan409() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        String rad = radicado();
        assertThat(upload(tenant, "ana", pdf(), "a.pdf", rad, 1, null, "k").getResponse().getStatus()).isEqualTo(201);

        MvcResult keyReuse = upload(tenant, "ana", pdf(), "b.pdf", radicado(), 1, null, "k");
        MvcResult sameTuple = upload(tenant, "ana", pdf(), "c.pdf", rad, 1, null, null);
        MvcResult newVersion = upload(tenant, "ana", pdf(), "d.pdf", rad, 2, null, null);

        assertThat(keyReuse.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(keyReuse).path("errorCode").asText()).isEqualTo("DOC_IDEMPOTENCY_KEY_REUSED");
        assertThat(sameTuple.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(sameTuple).path("errorCode").asText()).isEqualTo("DOC_DUPLICATE_BUSINESS_KEY");
        assertThat(newVersion.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void a5_dedupDeDocumentoNoVisibleNoRevelaIdNiEstado() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        operator(tenant, "carla");
        byte[] content = pdf();
        String rad = radicado();
        MvcResult first = upload(tenant, "ana", content, "a.pdf", rad, 1, "ALTAMENTE_CONFIDENCIAL", "k-ana");

        MvcResult byHash = upload(tenant, "carla", content, "b.pdf", radicado(), 1, null, null);
        MvcResult byKey = upload(tenant, "carla", pdf(), "c.pdf", radicado(), 1, null, "k-ana");
        MvcResult byTuple = upload(tenant, "carla", pdf(), "d.pdf", rad, 1, null, null);
        MvcResult own = upload(tenant, "ana", content, "e.pdf", radicado(), 1, null, null);

        for (MvcResult r : new MvcResult[] {byHash, byKey, byTuple}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(r).path("errorCode").asText()).isEqualTo("DOC_DUPLICATE_BUSINESS_KEY");
            assertThat(r.getResponse().getContentAsString()).doesNotContain(body(first).path("id").asText())
                    .doesNotContain("EN_EXTRACCION");
        }
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(own).path("id").asText()).isEqualTo(body(first).path("id").asText());
    }

    @Test
    void ac02_textoRenombradoAPdfSeRechazaSinPersistirNiGuardarEnBucket() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        double before = meters.counter("idp_document_rejected_total", "reason", "INVALID_MAGIC_BYTES").count();

        MvcResult r = upload(tenant, "ana", "esto es texto plano".getBytes(StandardCharsets.UTF_8));

        assertThat(r.getResponse().getStatus()).isEqualTo(415);
        assertThat(body(r).path("errorCode").asText()).isEqualTo("DOC_INVALID_FORMAT");
        assertThat(store.keys(tenant)).isEmpty();
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select count(*) from document", Integer.class)))
                .isZero();
        assertThat(renderer.calls.get()).isZero();
        assertThat(meters.counter("idp_document_rejected_total", "reason", "INVALID_MAGIC_BYTES").count())
                .isEqualTo(before + 1);
    }

    @Test
    void ac02_metadatosInvalidosDan400() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        MvcResult badRadicado = upload(tenant, "ana", pdf(), "a.pdf", "123", 1, null, null);
        MvcResult badClass = upload(tenant, "ana", pdf(), "a.pdf", radicado(), 1, "SECRETO", null);
        MvcResult noTypology = mvc.perform(multipart("/v1/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf()))
                .param("radicado", radicado()).with(token(tenant, "ana"))).andReturn();

        assertThat(badRadicado.getResponse().getStatus()).isEqualTo(400);
        assertThat(badClass.getResponse().getStatus()).isEqualTo(400);
        assertThat(noTypology.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void ac03_pdfValidoSeGuardaCifradoSeRenderizaYEmiteLosEventosDelPipeline() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        byte[] content = pdf();

        MvcResult r = upload(tenant, "ana", content, "oficio.pdf", radicado(), 1, null, null);
        JsonNode doc = body(r);
        String id = doc.path("id").asText();

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(doc.path("status").asText()).isEqualTo("EN_EXTRACCION");
        assertThat(doc.path("classification").asText()).isEqualTo("CONFIDENCIAL");
        assertThat(doc.path("tenantId").asText()).isEqualTo(tenant);
        // original + 2 PNG + capa de texto, todo bajo el prefijo del tenant y cifrado
        assertThat(store.keys(tenant)).hasSize(4);
        byte[] storedOriginal = store.raw(tenant, "documents/" + id + "/original.enc");
        assertThat(storedOriginal).isNotEqualTo(content);
        assertThat(new String(storedOriginal, StandardCharsets.ISO_8859_1)).doesNotContain("%PDF-");
        // un lector independiente (como el de extraction-service) descifra por rutas canonicas y AAD compartidos
        var keyResolver = new com.idp.tenant.context.TenantKeyResolver(null, java.time.Duration.ZERO, java.time.Clock.systemUTC()) {
            @Override
            public TenantKeys resolve(String tenantId) {
                return new TenantKeys("documents", "audit");
            }
        };
        var reader = new com.idp.storage.EncryptedArtifactStore(store, crypto, keyResolver);
        UUID docId = UUID.fromString(id);
        assertThat(reader.get(tenant, docId, com.idp.storage.ArtifactKind.PAGE_PNG, 1)).containsExactly(1, 2, 3);
        assertThat(reader.get(tenant, docId, com.idp.storage.ArtifactKind.PAGE_PNG, 2)).containsExactly(4, 5);
        assertThat(reader.get(tenant, docId, com.idp.storage.ArtifactKind.ORIGINAL, 0)).isEqualTo(content);
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select count(*) from page_artifact where document_id = ?", Integer.class, UUID.fromString(id))))
                .isEqualTo(3);

        JsonNode rendered = outbox(tenant, "documento.renderizado").get(0);
        assertThat(rendered.path("pageCount").asInt()).isEqualTo(2);
        assertThat(rendered.path("pageArtifactKeys")).hasSize(2);
        assertThat(outbox(tenant, "documento.recibido")).hasSize(1);
        JsonNode solicitada = outbox(tenant, "extraccion.solicitada").get(0);
        assertThat(solicitada.path("typology").asText()).isEqualTo("EC");
        assertThat(solicitada.path("documentId").asText()).isEqualTo(id);
        // sin PII: ningun evento contiene el radicado
        outbox(tenant).forEach(e -> assertThat(e.toString()).doesNotContain(doc.path("radicado").asText()));
    }

    @Test
    void ac04_rendererCaidoAgotaReintentosYTransitaAFallido() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        renderer.mode = TestBeans.FakeRenderer.Mode.UNAVAILABLE;

        MvcResult r = upload(tenant, "ana", pdf());

        assertThat(body(r).path("status").asText()).isEqualTo("FALLIDO");
        assertThat(renderer.calls.get()).isEqualTo(3);
        JsonNode ev = outbox(tenant, "documento.rechazado").get(0);
        assertThat(ev.path("reasonCode").asText()).isEqualTo("RENDERER_FAILED");
        assertThat(outbox(tenant, "extraccion.solicitada")).isEmpty();
    }

    @Test
    void ac04_rendererQueRechazaElArchivoTransitaARechazadoSinReintentos() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        renderer.mode = TestBeans.FakeRenderer.Mode.REJECT;

        MvcResult r = upload(tenant, "ana", pdf());

        assertThat(body(r).path("status").asText()).isEqualTo("RECHAZADO");
        assertThat(renderer.calls.get()).isEqualTo(1);
        assertThat(outbox(tenant, "documento.rechazado").get(0).path("reasonCode").asText())
                .isEqualTo("MALWARE_DETECTED");
    }

    @Test
    void ac05_extraccionCompletadaAutoApruebaYEmiteExtraccionAprobadaUnaSolaVez() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        String id = uploadOk(tenant, "ana");
        String json = event("extraccion.completada", tenant, id);

        listener.onMessage(json);
        listener.onMessage(json); // reentrega del mismo eventId

        assertThat(statusOf(tenant, id)).isEqualTo("APROBADO");
        var aprobadas = outbox(tenant, "extraccion.aprobada");
        assertThat(aprobadas).hasSize(1);
        assertThat(aprobadas.get(0).path("approvedBy").asText()).isEqualTo("AUTO_STP");
    }

    @Test
    void ac06_requiereRevisionPasaAEnRevisionYNoEmiteEventos() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        String id = uploadOk(tenant, "ana");
        int before = outbox(tenant).size();

        listener.onMessage(event("extraccion.requiere_revision", tenant, id, "taskId", UUID.randomUUID().toString()));

        assertThat(statusOf(tenant, id)).isEqualTo("EN_REVISION");
        assertThat(outbox(tenant)).hasSize(before);
    }

    @Test
    void ac06_revisionCompletadaAprobadaORechazada() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        roles.grant(tenant, "rita", Roles.REVISOR);
        String approved = uploadOk(tenant, "ana");
        String rejected = uploadOk(tenant, "ana");
        for (String id : new String[] {approved, rejected}) {
            listener.onMessage(event("extraccion.requiere_revision", tenant, id, "taskId", UUID.randomUUID().toString()));
        }

        listener.onMessage(event("revision.completada", tenant, approved, "taskId", UUID.randomUUID().toString(),
                "action", "APROBADO", "reviewerId", "rita"));
        listener.onMessage(event("revision.completada", tenant, rejected, "taskId", UUID.randomUUID().toString(),
                "action", "RECHAZADO", "reviewerId", "rita"));

        assertThat(statusOf(tenant, approved)).isEqualTo("APROBADO");
        assertThat(statusOf(tenant, rejected)).isEqualTo("RECHAZADO");
        assertThat(outbox(tenant, "extraccion.aprobada")).hasSize(1);
        assertThat(outbox(tenant, "extraccion.aprobada").get(0).path("approvedBy").asText())
                .isEqualTo("HUMAN_REVIEWER");
    }

    @Test
    void revisionCompletadaCiegaNoCambiaElEstadoDelDocumento() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        roles.grant(tenant, "rita", Roles.REVISOR);
        String id = uploadOk(tenant, "ana");
        listener.onMessage(event("extraccion.requiere_revision", tenant, id, "taskId", UUID.randomUUID().toString()));

        // Una medicion de calidad (blindSample) jamas aprueba ni rechaza: el documento sigue su propio flujo.
        ObjectNode blind = (ObjectNode) JSON.readTree(event("revision.completada", tenant, id, "taskId",
                UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "rita"));
        blind.put("blindSample", true);
        listener.onMessage(blind.toString());

        assertThat(statusOf(tenant, id)).isEqualTo("EN_REVISION");
        assertThat(outbox(tenant, "extraccion.aprobada")).isEmpty();
    }

    @Test
    void a6_revisionCompletadaConRevisorSinRolOSegundoAprobadorInvalidoSeIgnora() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        roles.grant(tenant, "rita", Roles.REVISOR);
        roles.grant(tenant, "rosa", Roles.REVISOR);
        String id = uploadOk(tenant, "ana");
        listener.onMessage(event("extraccion.requiere_revision", tenant, id, "taskId", UUID.randomUUID().toString()));
        double before = meters.counter("idp_document_review_event_rejected_total").count();

        // reviewerId sin rol REVISOR vigente
        listener.onMessage(event("revision.completada", tenant, id, "taskId", UUID.randomUUID().toString(),
                "action", "APROBADO", "reviewerId", "intruso"));
        assertThat(statusOf(tenant, id)).isEqualTo("EN_REVISION");
        // correccion critica sin segundo aprobador, y con el mismo revisor como segundo
        ObjectNode noSecond = (ObjectNode) JSON.readTree(event("revision.completada", tenant, id, "taskId",
                UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "rita"));
        noSecond.put("criticalCorrection", true);
        listener.onMessage(noSecond.toString());
        ObjectNode sameSecond = (ObjectNode) JSON.readTree(event("revision.completada", tenant, id, "taskId",
                UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "rita", "secondReviewerId", "rita"));
        sameSecond.put("criticalCorrection", true);
        listener.onMessage(sameSecond.toString());
        assertThat(statusOf(tenant, id)).isEqualTo("EN_REVISION");
        assertThat(meters.counter("idp_document_review_event_rejected_total").count()).isEqualTo(before + 3);

        // un evento con reviewerId ausente incumple el contrato
        org.junit.jupiter.api.Assertions.assertThrows(com.idp.events.EventValidationException.class,
                () -> listener.onMessage(event("revision.completada", tenant, id, "taskId",
                        UUID.randomUUID().toString(), "action", "APROBADO")));
        assertThat(outbox(tenant, "extraccion.aprobada")).isEmpty();

        // correccion critica con segundo aprobador distinto y con rol: se aprueba
        ObjectNode ok = (ObjectNode) JSON.readTree(event("revision.completada", tenant, id, "taskId",
                UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "rita", "secondReviewerId", "rosa"));
        ok.put("criticalCorrection", true);
        listener.onMessage(ok.toString());
        assertThat(statusOf(tenant, id)).isEqualTo("APROBADO");
    }

    @Test
    void eventosFueraDeSecuenciaOMalformadosSeTratanConSeguridad() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        String id = uploadOk(tenant, "ana");
        listener.onMessage(event("extraccion.completada", tenant, id));

        // APROBADO no vuelve a EN_REVISION (Regla 2) y un documento inexistente se ignora
        listener.onMessage(event("extraccion.requiere_revision", tenant, id, "taskId", UUID.randomUUID().toString()));
        listener.onMessage(event("extraccion.completada", tenant, UUID.randomUUID().toString()));
        listener.onMessage(event("documento.recibido", tenant, id)); // tipo no manejado: se ignora

        assertThat(statusOf(tenant, id)).isEqualTo("APROBADO");
        org.junit.jupiter.api.Assertions.assertThrows(com.idp.events.EventValidationException.class,
                () -> listener.onMessage("no-es-json"));
        org.junit.jupiter.api.Assertions.assertThrows(com.idp.events.EventValidationException.class,
                () -> listener.onMessage("{\"eventType\":\"extraccion.completada\",\"schemaVersion\":1}"));
    }

    @Test
    void ac07_altamenteConfidencialExigeDataStewardDistintoDelCargador() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        roles.grant(tenant, "ana", Roles.DATA_STEWARD); // el cargador tambien es steward: no puede auto-aprobar
        roles.grant(tenant, "bruno", Roles.DATA_STEWARD);
        roles.grant(tenant, "carla", Roles.OPERADOR);
        MvcResult r = upload(tenant, "ana", pdf(), "a.pdf", radicado(), 1, "ALTAMENTE_CONFIDENCIAL", null);
        String id = body(r).path("id").asText();

        listener.onMessage(event("extraccion.completada", tenant, id));
        assertThat(statusOf(tenant, id)).isEqualTo("APROBADO_PENDIENTE_STEWARD");
        assertThat(outbox(tenant, "extraccion.aprobada")).isEmpty();

        int self = mvc.perform(post("/v1/documents/" + id + "/approve-confidential").with(token(tenant, "ana")))
                .andReturn().getResponse().getStatus();
        int noRole = mvc.perform(post("/v1/documents/" + id + "/approve-confidential").with(token(tenant, "carla")))
                .andReturn().getResponse().getStatus();
        assertThat(self).isEqualTo(403);
        assertThat(noRole).isEqualTo(403);
        assertThat(statusOf(tenant, id)).isEqualTo("APROBADO_PENDIENTE_STEWARD");

        MvcResult ok = mvc.perform(post("/v1/documents/" + id + "/approve-confidential")
                .with(token(tenant, "bruno"))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).path("status").asText()).isEqualTo("APROBADO");
        assertThat(outbox(tenant, "extraccion.aprobada").get(0).path("approvedBy").asText())
                .isEqualTo("DATA_STEWARD");

        int again = mvc.perform(post("/v1/documents/" + id + "/approve-confidential").with(token(tenant, "bruno")))
                .andReturn().getResponse().getStatus();
        assertThat(again).isEqualTo(409);
    }

    @Test
    void ac07_documentoAltamenteConfidencialSoloLoVenSuCargadorYLosPrivilegiados() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        operator(tenant, "carla");
        roles.grant(tenant, "bruno", Roles.DATA_STEWARD);
        String id = body(upload(tenant, "ana", pdf(), "a.pdf", radicado(), 1, "ALTAMENTE_CONFIDENCIAL", null))
                .path("id").asText();

        assertThat(mvc.perform(get("/v1/documents/" + id).with(token(tenant, "ana"))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/v1/documents/" + id).with(token(tenant, "bruno"))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/v1/documents/" + id).with(token(tenant, "carla"))).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/v1/documents/" + id + "/download").with(token(tenant, "carla"))).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        JsonNode listCarla = body(mvc.perform(get("/v1/documents").with(token(tenant, "carla"))).andReturn());
        JsonNode listBruno = body(mvc.perform(get("/v1/documents").with(token(tenant, "bruno"))).andReturn());
        assertThat(listCarla.path("total").asInt()).isZero();
        assertThat(listBruno.path("total").asInt()).isEqualTo(1);
    }

    @Test
    void ac08_usuarioDeOtroTenantRecibe404Estricto() throws Exception {
        String tenantA = newTenant();
        String tenantB = newTenant();
        operator(tenantA, "ana");
        operator(tenantB, "beto");
        String idB = uploadOk(tenantB, "beto");

        MvcResult cross = mvc.perform(get("/v1/documents/" + idB).with(token(tenantA, "ana"))).andReturn();
        MvcResult unknown = mvc.perform(get("/v1/documents/" + UUID.randomUUID()).with(token(tenantA, "ana")))
                .andReturn();
        MvcResult crossDelete = mvc.perform(delete("/v1/documents/" + idB).with(token(tenantA, "ana"))).andReturn();

        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(cross).path("errorCode").asText()).isEqualTo(body(unknown).path("errorCode").asText());
        assertThat(body(cross).path("message").asText()).isEqualTo(body(unknown).path("message").asText());
        assertThat(crossDelete.getResponse().getStatus()).isIn(403, 404);
        assertThat(statusOf(tenantB, idB)).isEqualTo("EN_EXTRACCION");
    }

    @Test
    void ac08_elTenantSalesiempreDelTokenYNuncaDelCliente() throws Exception {
        String tenantA = newTenant();
        String tenantB = newTenant();
        operator(tenantB, "ana"); // ana solo tiene rol en B
        // token de A (sin rol alli) no puede operar aunque el usuario exista en otro tenant
        MvcResult r = upload(tenantA, "ana", pdf());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        MvcResult noTenantClaim = mvc.perform(get("/v1/documents")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .jwt().jwt(j -> j.subject("ana")))).andReturn();
        assertThat(noTenantClaim.getResponse().getStatus()).isEqualTo(403);
        MvcResult noToken = mvc.perform(get("/v1/documents")).andReturn();
        assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void ac09_purgaBorraBinariosAnulaCamposYEmiteDocumentoPurgado() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        roles.grant(tenant, "root", Roles.TENANT_ADMIN);
        String id = uploadOk(tenant, "ana");
        assertThat(store.keys(tenant)).hasSize(4);

        int noAdmin = mvc.perform(delete("/v1/documents/" + id).with(token(tenant, "ana"))).andReturn()
                .getResponse().getStatus();
        assertThat(noAdmin).isEqualTo(403);
        assertThat(store.keys(tenant)).hasSize(4);

        int status = mvc.perform(delete("/v1/documents/" + id).with(token(tenant, "root"))).andReturn()
                .getResponse().getStatus();

        assertThat(status).isEqualTo(204);
        assertThat(store.keys(tenant)).isEmpty();
        var row = inTenant(tenant, () -> jdbc.queryForMap("select * from document where id = ?", UUID.fromString(id)));
        assertThat(row.get("HASH_SHA256")).isNull();
        assertThat(row.get("RADICADO")).isNull();
        assertThat(row.get("OBJECT_STORE_KEY")).isNull();
        assertThat(row.get("UPLOADED_BY")).isNull();
        assertThat(row.get("PURGED_AT")).isNotNull();
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select count(*) from page_artifact", Integer.class)))
                .isZero();
        JsonNode purgado = outbox(tenant, "documento.purgado").get(0);
        assertThat(purgado.path("documentId").asText()).isEqualTo(id);
        assertThat(mvc.perform(get("/v1/documents/" + id).with(token(tenant, "root"))).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
        assertThat(mvc.perform(delete("/v1/documents/" + id).with(token(tenant, "root"))).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }

    @Test
    void k2_purgaBloqueadaPorLegalHoldDeDocumentoOTenantResponde409() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        roles.grant(tenant, "root", Roles.TENANT_ADMIN);
        String id = uploadOk(tenant, "ana");
        try {
            holds.holdDocument(tenant, UUID.fromString(id));
            assertThat(mvc.perform(delete("/v1/documents/" + id).with(token(tenant, "root"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(store.keys(tenant)).hasSize(4);

            holds.clear();
            holds.holdTenant(tenant);
            assertThat(mvc.perform(delete("/v1/documents/" + id).with(token(tenant, "root"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(store.keys(tenant)).hasSize(4);
            assertThat(outbox(tenant, "documento.purgado")).isEmpty();
        } finally {
            holds.clear();
        }
        assertThat(mvc.perform(delete("/v1/documents/" + id).with(token(tenant, "root"))).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void listadoPaginadoConFiltroDeEstado() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        for (int i = 0; i < 3; i++) {
            uploadOk(tenant, "ana");
        }
        renderer.mode = TestBeans.FakeRenderer.Mode.REJECT;
        upload(tenant, "ana", pdf());

        JsonNode page = body(mvc.perform(get("/v1/documents?limit=2&offset=0").with(token(tenant, "ana")))
                .andReturn());
        JsonNode rejected = body(mvc.perform(get("/v1/documents?status=RECHAZADO").with(token(tenant, "ana")))
                .andReturn());
        int badStatus = mvc.perform(get("/v1/documents?status=NOPE").with(token(tenant, "ana"))).andReturn()
                .getResponse().getStatus();

        assertThat(page.path("data")).hasSize(2);
        assertThat(page.path("total").asInt()).isEqualTo(4);
        assertThat(page.path("limit").asInt()).isEqualTo(2);
        assertThat(rejected.path("total").asInt()).isEqualTo(1);
        assertThat(badStatus).isEqualTo(400);
    }

    @Test
    void descargaGeneraEnlaceFirmadoQueEntregaElBinarioDescifradoYFallaConOtroUsuario() throws Exception {
        String tenant = newTenant();
        operator(tenant, "ana");
        operator(tenant, "beto");
        byte[] content = pdf();
        String id = body(upload(tenant, "ana", content)).path("id").asText();

        JsonNode link = body(mvc.perform(get("/v1/documents/" + id + "/download").with(token(tenant, "ana")))
                .andReturn());
        String url = link.path("downloadUrl").asText();
        assertThat(link.path("expiresAt").asText()).isNotBlank();
        String path = url.substring(url.indexOf("/v1/"));

        MvcResult ok = mvc.perform(get(path).with(token(tenant, "ana"))).andReturn();
        MvcResult tampered = mvc.perform(get(path.substring(0, path.indexOf("sig=")) + "sig=deadbeef").with(token(tenant, "ana")))
                .andReturn();
        MvcResult otherUser = mvc.perform(get(path).with(token(tenant, "beto"))).andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsByteArray()).isEqualTo(content);
        assertThat(ok.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment");
        assertThat(ok.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(tampered.getResponse().getStatus()).isEqualTo(403);
        assertThat(otherUser.getResponse().getStatus()).isEqualTo(403);
    }
}
