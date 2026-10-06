package com.idp.notification;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.notification.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.notification.event.NotificationKafkaListener;
import com.idp.notification.service.DeliveryWorker;
import com.idp.notification.support.FakeHostResolver;
import com.idp.notification.support.MutableClock;
import com.idp.notification.support.TestBeans;
import com.idp.notification.support.TestReceiver;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Base de pruebas de integracion: contexto completo con H2 (modo PostgreSQL), un silo H2 por tenant, DNS falso,
 * reloj controlable y un receptor HTTP real en loopback (la politica de pruebas solo exceptua loopback).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class AbstractIntegrationTest {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final String HOST = "hook.banco.test";

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TenantSchemaMigrator migrator;
    @Autowired protected TestBeans.MutableRoleSource roles;
    @Autowired protected FakeHostResolver dns;
    @Autowired protected MutableClock clock;
    @Autowired protected DeliveryWorker worker;
    @Autowired protected NotificationKafkaListener listener;

    protected TestReceiver receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new TestReceiver();
        dns.reset();
        dns.map(HOST, "127.0.0.1");
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    protected String newTenant() {
        String tenant = UUID.randomUUID().toString();
        migrator.migrate(tenant);
        return tenant;
    }

    protected String admin(String tenant) {
        roles.grant(tenant, "admin", Roles.TENANT_ADMIN);
        return "admin";
    }

    protected static RequestPostProcessor token(String tenant, String user) {
        return jwt().jwt(j -> j.subject(user).claim("tenant_id", tenant));
    }

    protected String hookUrl() {
        return "http://" + HOST + ":" + receiver.port() + "/webhook";
    }

    protected void allowHosts(String tenant, String... hosts) throws Exception {
        allowHosts(tenant, 5, 30, 2.0, 3600, hosts);
    }

    protected void allowHosts(String tenant, int maxAttempts, long initialSeconds, double multiplier, long maxSeconds,
                              String... hosts) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        var arr = body.putArray("allowedHosts");
        for (String h : hosts) {
            arr.add(h);
        }
        body.put("maxAttempts", maxAttempts);
        body.put("initialBackoffSeconds", initialSeconds);
        body.put("backoffMultiplier", multiplier);
        body.put("maxBackoffSeconds", maxSeconds);
        MvcResult r = mvc.perform(put("/v1/webhooks/policy").with(token(tenant, admin(tenant)))
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new IllegalStateException("policy " + r.getResponse().getStatus() + r.getResponse().getContentAsString());
        }
    }

    /** Crea un webhook por la API; devuelve la respuesta (con el secreto en claro). */
    protected JsonNode createWebhook(String tenant, String url, String... events) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("url", url);
        var arr = body.putArray("events");
        for (String e : events) {
            arr.add(e);
        }
        MvcResult r = mvc.perform(post("/v1/webhooks").with(token(tenant, admin(tenant)))
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException("create " + r.getResponse().getStatus() + r.getResponse().getContentAsString());
        }
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    protected JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    /** Evento plano conforme a contracts/events (extraccion.aprobada por defecto). */
    protected static String event(String type, String tenant, String documentId, String... extra) {
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

    protected static String aprobada(String tenant, String documentId) {
        return event("extraccion.aprobada", tenant, documentId, "approvedBy", "AUTO_STP");
    }

    protected <T> T inTenant(String tenant, java.util.function.Supplier<T> work) {
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenant);
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

    protected List<JsonNode> outbox(String tenant, String eventType) {
        return inTenant(tenant, () -> {
            List<JsonNode> out = new ArrayList<>();
            for (String payload : jdbc.queryForList("select payload from outbox where event_type = ? order by seq",
                String.class, eventType)) {
                try {
                    out.add(JSON.readTree(payload));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        });
    }

    protected List<java.util.Map<String, Object>> deliveries(String tenant) {
        return inTenant(tenant, () -> jdbc.queryForList(
            "select id, webhook_id, document_id, status, attempts, error_code, last_http_status, next_attempt_at "
                + "from webhook_delivery order by created_at, id"));
    }

    protected int runWorker(String tenant) {
        return worker.runTenant(tenant);
    }
}
