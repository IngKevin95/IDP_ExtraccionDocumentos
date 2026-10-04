package com.idp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** SEC-002: la asignacion vigente se evalua contra la base de control. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcRoleAssignmentSourceIntegrationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcRoleAssignmentSource source;
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("create table if not exists role_assignment (id uuid primary key, tenant_id uuid not null, "
            + "user_id varchar(255) not null, role varchar(50) not null, expires_at timestamptz, "
            + "deleted_at timestamptz)");
        jdbc.update("delete from role_assignment");
        source = new JdbcRoleAssignmentSource(jdbc);
    }

    private void grant(String user, String role, String expires, boolean deleted) {
        jdbc.update("insert into role_assignment (id, tenant_id, user_id, role, expires_at, deleted_at) "
            + "values (?, ?, ?, ?, " + (expires == null ? "null" : "now() + interval '" + expires + "'") + ", "
            + (deleted ? "now()" : "null") + ")", UUID.randomUUID(), tenant, user, role);
    }

    @Test
    void asignacionVigenteSeReconoce() {
        grant("u1", "ANALISTA", null, false);
        assertTrue(source.hasRole(tenant.toString(), "u1", "ANALISTA"));
    }

    @Test
    void asignacionBorradaOVencidaOAjenaSeDeniega() {
        grant("u2", "ANALISTA", null, true);
        grant("u3", "ANALISTA", "-1 hour", false);
        grant("u4", "ANALISTA", null, false);
        assertFalse(source.hasRole(tenant.toString(), "u2", "ANALISTA"));
        assertFalse(source.hasRole(tenant.toString(), "u3", "ANALISTA"));
        assertFalse(source.hasRole(UUID.randomUUID().toString(), "u4", "ANALISTA"));
        assertFalse(source.hasRole("no-es-uuid", "u4", "ANALISTA"));
    }
}
