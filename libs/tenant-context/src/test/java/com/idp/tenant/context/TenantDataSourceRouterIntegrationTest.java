package com.idp.tenant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** silo-tenant AC-03: cada tenant usa exclusivamente su propia base. */
@Testcontainers(disabledWithoutDocker = true)
class TenantDataSourceRouterIntegrationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void ac03_dosTenantesVenDatosDistintos() {
        TenantCredentialProvider provider = tenantId -> new TenantConnection(PG.getJdbcUrl(), PG.getUsername(),
            PG.getPassword());
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 2, 2);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(router);
            TenantContextHolder.setTenantId("t1");
            jdbc.execute("create table if not exists marca(valor text)");
            Map<String, Object> row = jdbc.queryForMap("select current_user as u");
            assertEquals(PG.getUsername(), row.get("u"));
            TenantContextHolder.setTenantId("t2");
            assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
            assertEquals(2, router.poolCount());
        } finally {
            router.destroy();
        }
    }
}
