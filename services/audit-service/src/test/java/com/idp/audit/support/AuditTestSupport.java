package com.idp.audit.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.application.AuditIngestionService;
import com.idp.events.EventEnvelope;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.kms.InMemoryKeyService;
import com.idp.security.CachingRoleAssignmentVerifier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de tests: contexto completo con H2 (modo PostgreSQL), Flyway real y JWT de prueba. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class AuditTestSupport {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final EventSerde SERDE = new EventSerde();

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected IdempotentEventConsumer consumer;
    @Autowired protected AuditIngestionService ingestion;
    @Autowired protected InMemoryKeyService keys;
    @Autowired protected RecordingImmutableStore store;
    @Autowired protected CapturingPublisher publisher;
    @Autowired protected MutableClock clock;
    @Autowired protected CachingRoleAssignmentVerifier roleCache;

    @BeforeEach
    void resetClock() {
        clock.reset();
        store.failPuts = false;
    }

    protected static UUID newTenant() {
        return UUID.randomUUID();
    }

    /** Evento valido segun contracts/events: extraccion.aprobada. */
    protected static EventEnvelope aprobada(UUID tenant, UUID documentId) {
        ObjectNode p = JSON.createObjectNode();
        p.put("documentId", documentId.toString());
        p.put("approvedBy", "HUMAN_REVIEWER");
        return new EventEnvelope(UUID.randomUUID(), "extraccion.aprobada", 1, Instant.now(), tenant,
                UUID.randomUUID(), p);
    }

    protected static EventEnvelope recibida(UUID tenant, UUID documentId) {
        ObjectNode p = JSON.createObjectNode();
        p.put("documentId", documentId.toString());
        p.put("hashSha256", "a".repeat(64));
        p.put("typology", "EMB");
        p.put("version", 1);
        p.put("classification", "CONFIDENCIAL");
        p.put("objectStoreKey", "docs/" + documentId);
        return new EventEnvelope(UUID.randomUUID(), "documento.recibido", 1, Instant.now(), tenant,
                UUID.randomUUID(), p);
    }

    protected static EventEnvelope purgado(UUID tenant, UUID documentId) {
        ObjectNode p = JSON.createObjectNode();
        p.put("documentId", documentId.toString());
        p.put("purgedAt", Instant.now().toString());
        return new EventEnvelope(UUID.randomUUID(), "documento.purgado", 1, Instant.now(), tenant,
                UUID.randomUUID(), p);
    }

    protected static EventEnvelope breakGlass(UUID tenant) {
        ObjectNode p = JSON.createObjectNode();
        p.put("subjectId", "sre1");
        p.put("approvedBy", "admin2");
        p.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        return new EventEnvelope(UUID.randomUUID(), "breakglass.otorgado", 1, Instant.now(), tenant,
                UUID.randomUUID(), p);
    }

    /** Ingesta por el camino real del consumidor Kafka (validacion de schema + idempotencia + cadena). */
    protected IdempotentEventConsumer.Result consume(EventEnvelope e) {
        return consumer.consume(SERDE.toJson(e), ingestion::ingest);
    }

    protected long countEntries(UUID tenant) {
        return jdbc.sql("select count(*) from audit_entries where tenant_id = :t").param("t", tenant)
                .query(Long.class).single();
    }

    protected void grantAuditor(UUID tenant, String user) {
        jdbc.sql("insert into role_assignment (id, tenant_id, user_id, role) values (:id, :t, :u, 'AUDITOR')")
                .param("id", UUID.randomUUID()).param("t", tenant).param("u", user).update();
    }

    protected static RequestPostProcessor token(UUID tenant, String user) {
        return jwt().jwt(j -> j.subject(user).claim("tenant_id", tenant.toString()));
    }

    protected static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }
}
