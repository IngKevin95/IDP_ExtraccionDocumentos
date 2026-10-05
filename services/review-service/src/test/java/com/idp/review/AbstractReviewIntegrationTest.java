package com.idp.review;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.review.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.review.domain.FieldCandidate;
import com.idp.review.infra.ReviewEventListener;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de integracion: contexto completo con H2 (modo PostgreSQL), un silo H2 por tenant. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
abstract class AbstractReviewIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaMigrator migrator;
    @Autowired TestBeans.MutableRoleSource roles;
    @Autowired TestBeans.StubFields fields;
    @Autowired TestBeans.InMemoryObjectStore store;
    @Autowired ReviewEventListener listener;

    String tenant;

    @BeforeEach
    void freshTenant() {
        tenant = newTenant();
    }

    String newTenant() {
        String t = UUID.randomUUID().toString();
        migrator.migrate(t);
        return t;
    }

    static RequestPostProcessor token(String tenant, String user) {
        return jwt().jwt(j -> j.subject(user).claim("tenant_id", tenant));
    }

    String reviewer(String tenantId, String user) {
        roles.grant(tenantId, user, Roles.REVISOR);
        return user;
    }

    String reviewer(String user) {
        return reviewer(tenant, user);
    }

    String admin(String user) {
        roles.grant(tenant, user, Roles.TENANT_ADMIN);
        return user;
    }

    static FieldCandidate field(String name, Integer page, String bbox) {
        return new FieldCandidate(name, page, bbox, "valor-" + name, new BigDecimal("0.7000"));
    }

    /** Crea una tarea consumiendo extraccion.requiere_revision; devuelve su taskId. */
    UUID createTask(FieldCandidate... candidates) {
        UUID taskId = UUID.randomUUID();
        fields.program(taskId, candidates);
        listener.onMessage(requiereRevision(tenant, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                taskId.toString()));
        return taskId;
    }

    static String requiereRevision(String tenant, String eventId, String documentId, String taskId) {
        ObjectNode n = JSON.createObjectNode();
        n.put("eventId", eventId);
        n.put("eventType", "extraccion.requiere_revision");
        n.put("schemaVersion", 1);
        n.put("occurredAt", java.time.Instant.now().toString());
        n.put("tenantId", tenant);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", documentId);
        n.put("taskId", taskId);
        return n.toString();
    }

    MvcResult post(String user, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(token(tenant, user))).andReturn();
    }

    MvcResult postJson(String user, String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(token(tenant, user)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    MvcResult getAs(String tenantId, String user, String path) throws Exception {
        return mvc.perform(get(path).with(token(tenantId, user))).andReturn();
    }

    MvcResult getAs(String user, String path) throws Exception {
        return getAs(tenant, user, path);
    }

    static JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    String taskStatus(UUID taskId) {
        return inTenant(tenant, () -> jdbc.queryForObject("select status from review_task where id = ?",
                String.class, taskId));
    }

    <T> T inTenant(String tenantId, java.util.function.Supplier<T> work) {
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

    void exec(String sql, Object... args) {
        inTenant(tenant, () -> jdbc.update(sql, args));
    }
}
