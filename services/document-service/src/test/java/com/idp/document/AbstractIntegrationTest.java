package com.idp.document;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.document.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.tenant.context.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de pruebas de integracion: contexto completo con H2 (modo PostgreSQL), un silo H2 por tenant. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
@org.springframework.test.context.TestPropertySource(properties = {
    "idp.tenant-db.jdbc-url-template=jdbc:h2:mem:{tenant};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
abstract class AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaMigrator migrator;
    @Autowired TestBeans.InMemoryObjectStore store;
    @Autowired TestBeans.MutableRoleSource roles;
    @Autowired TestBeans.MutableHoldGate holds;
    @Autowired TestBeans.FakeRenderer renderer;
    @Autowired com.idp.kms.EnvelopeCrypto crypto;
    @Autowired com.idp.tenant.context.TenantKeyResolver keyResolver;

    @BeforeEach
    void resetRenderer() {
        renderer.reset();
    }

    String newTenant() {
        String tenant = UUID.randomUUID().toString();
        migrator.migrate(tenant);
        return tenant;
    }

    static RequestPostProcessor token(String tenant, String user) {
        return jwt().jwt(j -> j.subject(user).claim("tenant_id", tenant));
    }

    static String radicado() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 23; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    static byte[] pdf() {
        return ("%PDF-1.4 " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
    }

    MvcResult upload(String tenant, String user, byte[] content, String filename, String radicado, int version,
                     String classification, String idempotencyKey) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/v1/documents")
                .file(new MockMultipartFile("file", filename, "application/pdf", content))
                .param("typology", "EC").param("radicado", radicado).param("version", String.valueOf(version))
                .with(token(tenant, user));
        if (classification != null) {
            req.param("classification", classification);
        }
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(req).andReturn();
    }

    MvcResult upload(String tenant, String user, byte[] content) throws Exception {
        return upload(tenant, user, content, "oficio.pdf", radicado(), 1, null, null);
    }

    static JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    <T> T inTenant(String tenant, java.util.function.Supplier<T> work) {
        TenantContextHolder.setTenantId(tenant);
        try {
            return work.get();
        } finally {
            TenantContextHolder.clear();
        }
    }

    List<JsonNode> outbox(String tenant) {
        return inTenant(tenant, () -> {
            List<JsonNode> out = new ArrayList<>();
            for (String payload : jdbc.queryForList("select payload from outbox order by created_at, id",
                    String.class)) {
                try {
                    out.add(JSON.readTree(payload));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        });
    }

    List<JsonNode> outbox(String tenant, String eventType) {
        return outbox(tenant).stream().filter(n -> eventType.equals(n.path("eventType").asText())).toList();
    }

    String statusOf(String tenant, String documentId) {
        return inTenant(tenant, () -> jdbc.queryForObject("select status from document where id = ?", String.class,
                UUID.fromString(documentId)));
    }

    /** JSON plano de un evento entrante del pipeline, conforme a contracts/events. */
    static String event(String type, String tenant, String documentId, String... extra) {
        ObjectNode n = JSON.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", type);
        n.put("schemaVersion", 1);
        n.put("occurredAt", java.time.Instant.now().toString());
        n.put("tenantId", tenant);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", documentId);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            n.put(extra[i], extra[i + 1]);
        }
        return n.toString();
    }
}
