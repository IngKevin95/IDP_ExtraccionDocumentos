package com.idp.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idp.tenant.support.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Flyway, JSONB, outbox y revalidacion contra PostgreSQL real (se omite sin Docker; corre en CI). */
@Testcontainers(disabledWithoutDocker = true)
class TenantPostgresIntegrationTest extends ApiTestSupport {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void altaCuotaYRevalidacionFuncionanSobrePostgres() throws Exception {
        UUID t = createTenantWithAdmin("boss");
        assertEquals("ACTIVE", jdbc.sql("SELECT status FROM tenants WHERE id = :t").param("t", t)
                .query(String.class).single());
        event(t, "tenant.aprovisionado");
        mvc.perform(post("/v1/admin/tenants/" + t + "/consumption").with(platformAdmin("admin1"))
                .contentType("application/json").content("{\"metricName\":\"PAGES_RENDERED\",\"value\":9000}"))
                .andExpect(status().isAccepted());
        event(t, "cuota.umbral_alcanzado");
        mvc.perform(get("/v1/tenant/users").with(tenantUser(t, "boss"))).andExpect(status().isOk());
    }
}
