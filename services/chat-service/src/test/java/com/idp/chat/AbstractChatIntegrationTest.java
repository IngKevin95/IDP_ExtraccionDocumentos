package com.idp.chat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.chat.config.ChatProperties;
import com.idp.chat.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.chat.infra.ChatEventListener;
import com.idp.events.EventSchemaValidator;
import com.idp.kms.EnvelopeCrypto;
import com.idp.security.Roles;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import com.idp.tenant.context.TenantContextHolder;
import com.idp.tenant.context.TenantKeyResolver;
import com.idp.testsupport.Topics;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base de integracion: contexto completo contra PostgreSQL con pgvector (Testcontainers). Cada tenant tiene su propia
 * base (silo) migrada con el TenantSchemaMigrator real; los proveedores de IA, el bucket y los roles son dobles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
abstract class AbstractChatIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void silo(DynamicPropertyRegistry registry) {
        registry.add("idp.tenant-db.jdbc-url-template", PgVector::urlTemplate);
        registry.add("idp.tenant-db.username", PgVector.PG::getUsername);
        registry.add("idp.tenant-db.password", PgVector.PG::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaMigrator migrator;
    @Autowired TestBeans.MutableRoleSource roles;
    @Autowired TestBeans.MutableTenantDirectory directory;
    @Autowired TestBeans.InMemoryObjectStore store;
    @Autowired TestBeans.FakeEmbeddings embeddings;
    @Autowired TestBeans.ScriptedLlm llm;
    @Autowired ChatEventListener listener;
    @Autowired EnvelopeCrypto crypto;
    @Autowired TenantKeyResolver keys;
    @Autowired EventSchemaValidator validator;
    @Autowired ChatProperties props;
    @Autowired com.idp.chat.service.ContentCipher cipher;
    @Autowired com.idp.kms.KeyService keyService;

    String tenant;

    @BeforeEach
    void freshTenant() {
        llm.reset();
        tenant = newTenant();
    }

    // ---- tenants y documentos ----------------------------------------------------------------------------------

    String newTenant() {
        String t = UUID.randomUUID().toString();
        PgVector.createDatabase(t);
        migrator.migrate(t);
        inTenant(t, () -> jdbc.update("create table document (id uuid primary key, tenant_id varchar(64) not null, "
                + "status varchar(32), classification varchar(32) not null default 'CONFIDENCIAL', "
                + "uploaded_by varchar(128), purged_at timestamp with time zone)"));
        directory.add(t);
        return t;
    }

    void registerDocument(String tenantId, UUID documentId, String classification, String uploadedBy) {
        inTenant(tenantId, () -> jdbc.update("insert into document (id, tenant_id, status, classification, uploaded_by) "
                + "values (?, ?, 'APROBADO', ?, ?)", documentId, tenantId, classification, uploadedBy));
    }

    void putTextLayer(String tenantId, UUID documentId, String... pages) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("pages");
        for (int i = 0; i < pages.length; i++) {
            arr.addObject().put("page", i + 1).put("text", pages[i]);
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        new EncryptedArtifactStore((ObjectStore) store, crypto, keys).put(tenantId, documentId, ArtifactKind.TEXT_LAYER,
                0, bytes);
    }

    /** Registra el documento, escribe su capa de texto y procesa extraccion.aprobada. */
    UUID indexedDocumentAs(String classification, String uploadedBy, String... pages) {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, classification, uploadedBy);
        putTextLayer(tenant, doc, pages);
        approve(tenant, doc);
        return doc;
    }

    UUID indexedDocument(String... pages) {
        return indexedDocumentAs("CONFIDENCIAL", "uploader", pages);
    }

    void approve(String tenantId, UUID documentId) {
        Topics.deliver(listener::onMessage, aprobada(tenantId, UUID.randomUUID(), documentId));
    }

    static String aprobada(String tenantId, UUID eventId, UUID documentId) {
        ObjectNode n = JSON.createObjectNode();
        n.put("eventId", eventId.toString());
        n.put("eventType", "extraccion.aprobada");
        n.put("schemaVersion", 1);
        n.put("occurredAt", java.time.Instant.now().toString());
        n.put("tenantId", tenantId);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", documentId.toString());
        n.put("approvedBy", "AUTO_STP");
        return n.toString();
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    String operator(String user) {
        roles.grant(tenant, user, Roles.OPERADOR);
        return user;
    }

    static RequestPostProcessor token(String tenantId, String user) {
        return jwt().jwt(j -> j.subject(user).claim("tenant_id", tenantId));
    }

    MvcResult createSession(String user, UUID documentId) throws Exception {
        return mvc.perform(post("/v1/chat/sessions").with(token(tenant, user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + documentId + "\"}")).andReturn();
    }

    UUID session(String user, UUID documentId) throws Exception {
        MvcResult r = createSession(user, documentId);
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException("createSession " + r.getResponse().getStatus() + " "
                    + r.getResponse().getContentAsString());
        }
        return UUID.fromString(body(r).path("id").asText());
    }

    MvcResult ask(String user, UUID sessionId, String content) throws Exception {
        return askAs(tenant, user, sessionId, content);
    }

    MvcResult askAs(String tenantId, String user, UUID sessionId, String content) throws Exception {
        ObjectNode n = JSON.createObjectNode();
        n.put("content", content);
        return mvc.perform(post("/v1/chat/sessions/" + sessionId + "/messages").with(token(tenantId, user))
                .contentType(MediaType.APPLICATION_JSON).content(n.toString())).andReturn();
    }

    static JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    /** Texto en claro de los fragmentos del documento (en la base viven cifrados), de una pagina o de todas. */
    List<String> chunkTexts(UUID documentId, Integer page) {
        return inTenant(tenant, () -> jdbc.query("select id, content_enc from chunk where document_id = ? "
                + (page == null ? "" : "and page_number = " + page) + " order by ordinal",
                (rs, i) -> cipher.decrypt(tenant, documentId, rs.getObject("id", UUID.class),
                        com.idp.chat.service.ContentCipher.FIELD_CHUNK, rs.getBytes("content_enc")), documentId));
    }

    /** Crypto-shredding en pruebas: destruye la KEK de datos del tenant. */
    void destroyDataKek(String tenantId) {
        keyService.disableKek(new com.idp.tenant.TenantId(tenantId), keys.resolve(tenantId).dataKekId());
    }

    // ---- base de datos del silo --------------------------------------------------------------------------------

    <T> T inTenant(String tenantId, Supplier<T> work) {
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    int count(String sql, Object... args) {
        return inTenant(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    /** Payloads de outbox del tenant para un eventType (JSON plano). */
    List<JsonNode> outbox(String eventType) {
        return inTenant(tenant, () -> {
            List<JsonNode> out = new ArrayList<>();
            for (String payload : jdbc.queryForList("select payload from outbox where event_type = ? "
                    + "order by created_at, id", String.class, eventType)) {
                try {
                    out.add(JSON.readTree(payload));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        });
    }

    /** Todo evento del outbox cumple su JSON Schema y no contiene ninguno de los textos dados (SEC-050). */
    void assertEventsClean(String... forbiddenTexts) {
        List<String> payloads = inTenant(tenant, () -> jdbc.queryForList("select payload from outbox", String.class));
        for (String p : payloads) {
            try {
                validator.validateFlat(JSON.readTree(p));
            } catch (Exception e) {
                throw new AssertionError("Evento fuera de contrato: " + p, e);
            }
            for (String f : forbiddenTexts) {
                if (p.contains(f)) {
                    throw new AssertionError("El evento contiene contenido: " + f);
                }
            }
        }
    }
}
