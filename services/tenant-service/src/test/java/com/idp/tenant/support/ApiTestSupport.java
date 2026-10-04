package com.idp.tenant.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.tenant.domain.RoleAssignment;
import com.idp.tenant.infrastructure.persistence.RoleAssignmentRepository;
import com.jayway.jsonpath.JsonPath;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de tests de API: contexto completo con H2 (modo PostgreSQL), Flyway real y JWT de prueba. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class ApiTestSupport {
    protected static final UUID STANDARD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID DEDICATED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected ControllableProvisioningPort port;
    @Autowired protected InMemoryKeyService keys;
    @Autowired protected RoleAssignmentRepository roles;

    @BeforeEach
    void resetPort() {
        port.failBucket = false;
    }

    protected static RequestPostProcessor platformAdmin(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    protected static RequestPostProcessor tenantUser(UUID tenant, String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenant.toString()));
    }

    protected UUID createTenant() throws Exception {
        String body = mvc.perform(post("/v1/admin/tenants").with(platformAdmin("admin1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banco " + UUID.randomUUID() + "\",\"planId\":\"" + STANDARD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Alta de tenant con un TENANT_ADMIN sembrado directamente en la base de control. */
    protected UUID createTenantWithAdmin(String adminUser) throws Exception {
        UUID t = createTenant();
        insertRole(t, adminUser, "TENANT_ADMIN", null);
        return t;
    }

    protected void insertRole(UUID tenant, String user, String role, Instant expiresAt) {
        roles.insert(new RoleAssignment(UUID.randomUUID(), tenant, user, role, "seed", null, expiresAt,
                Instant.now(), null, null, null, null));
    }

    protected List<String> events(UUID tenant, String type) {
        return jdbc.sql("SELECT CAST(payload AS VARCHAR) FROM outbox WHERE aggregate_id = :t AND type = :ty "
                        + "ORDER BY created_at, id")
                .param("t", tenant.toString()).param("ty", type).query(String.class).list();
    }

    /** Valida el evento contra el JSON Schema versionado de contracts/events. */
    protected static void assertValid(String eventType, String json) throws Exception {
        String schemaText = Files.readString(Path.of("../../contracts/events/" + eventType + ".v1.schema.json"));
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaText);
        Set<ValidationMessage> errors = schema.validate(json, InputFormat.JSON);
        if (!errors.isEmpty()) {
            throw new AssertionError("Evento " + eventType + " invalido: " + errors + " -> " + json);
        }
    }

    /** Devuelve el unico evento del tipo para el tenant, validado contra su schema. */
    protected JsonNode event(UUID tenant, String type) throws Exception {
        List<String> all = events(tenant, type);
        if (all.size() != 1) {
            throw new AssertionError("Se esperaba 1 evento " + type + " y hay " + all.size());
        }
        assertValid(type, all.get(0));
        return JSON.readTree(all.get(0));
    }
}
