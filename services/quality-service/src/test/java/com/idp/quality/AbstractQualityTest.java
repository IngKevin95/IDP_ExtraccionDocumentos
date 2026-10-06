package com.idp.quality;

import com.idp.testsupport.Topics;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.quality.kafka.QualityEventConsumer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de integracion: contexto completo con H2 en modo PostgreSQL, un tenant nuevo por prueba. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class AbstractQualityTest {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected QualityEventConsumer consumer;
    @Autowired protected TestBeans.MutableRoleSource roles;
    @Autowired protected TestBeans.FakeRunner runner;

    protected static RequestPostProcessor token(String tenant, String user) {
        return jwt().jwt(j -> j.subject(user).claim("tenant_id", tenant));
    }

    protected String steward(String tenant) {
        String user = "steward-" + UUID.randomUUID();
        roles.grant(tenant, user, "DATA_STEWARD");
        return user;
    }

    protected static JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    protected static Instant at(LocalDate day) {
        return day.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    /** Evento plano conforme a contracts/events; {@code extra} son pares campo, valor de texto. */
    protected static ObjectNode event(String type, String tenant, UUID doc, Instant at, String... extra) {
        ObjectNode n = JSON.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", type);
        n.put("schemaVersion", 1);
        n.put("occurredAt", at.toString());
        n.put("tenantId", tenant);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", doc.toString());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            n.put(extra[i], extra[i + 1]);
        }
        return n;
    }

    protected static ObjectNode aprobada(String tenant, Instant at, String approvedBy, String typology) {
        return event("extraccion.aprobada", tenant, UUID.randomUUID(), at, "approvedBy", approvedBy, "typology",
            typology);
    }

    protected static ObjectNode revision(String tenant, Instant at, String typology, String action,
                                         boolean blind, String... correctedFields) {
        ObjectNode n = event("revision.completada", tenant, UUID.randomUUID(), at, "taskId",
            UUID.randomUUID().toString(), "action", action, "reviewerId", "revisor-1", "typology", typology);
        if (blind) {
            n.put("blindSample", true);
        }
        ArrayNode arr = n.putArray("correctedFields");
        for (String f : correctedFields) {
            arr.addObject().put("field", f).put("correctionType", "VALOR");
        }
        return n;
    }

    protected void send(ObjectNode event) {
        Topics.deliver(consumer::onMessage, event.toString());
    }

    protected int countRows(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
