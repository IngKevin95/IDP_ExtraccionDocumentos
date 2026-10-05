package com.idp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A1: roles normales exigen tenant ACTIVE; los de supervision sobreviven a PENDING_DELETION pero no a DELETED. */
class JdbcRoleAssignmentSourceH2Test {

    private JdbcTemplate jdbc;
    private JdbcRoleAssignmentSource source;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:ra" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table tenants (id uuid primary key, status varchar(50) not null)");
        jdbc.execute("create table role_assignment (id uuid primary key, tenant_id uuid not null, "
            + "user_id varchar(255) not null, role varchar(50) not null, expires_at timestamp with time zone, "
            + "deleted_at timestamp with time zone)");
        source = new JdbcRoleAssignmentSource(jdbc);
    }

    private UUID tenant(String status, String user, String role) {
        UUID t = UUID.randomUUID();
        jdbc.update("insert into tenants values (?, ?)", t, status);
        jdbc.update("insert into role_assignment (id, tenant_id, user_id, role) values (?, ?, ?, ?)",
            UUID.randomUUID(), t, user, role);
        return t;
    }

    @Test
    void rolNormalSoloConTenantActive() {
        UUID active = tenant("ACTIVE", "u", Roles.OPERADOR);
        UUID pending = tenant("PENDING_DELETION", "u", Roles.OPERADOR);
        UUID deleted = tenant("DELETED", "u", Roles.OPERADOR);
        UUID creating = tenant("CREATING", "u", Roles.TENANT_ADMIN);
        assertTrue(source.hasRole(active.toString(), "u", Roles.OPERADOR));
        assertFalse(source.hasRole(pending.toString(), "u", Roles.OPERADOR));
        assertFalse(source.hasRole(deleted.toString(), "u", Roles.OPERADOR));
        assertFalse(source.hasRole(creating.toString(), "u", Roles.TENANT_ADMIN));
    }

    @Test
    void rolesDeSupervisionSobrevivenALaBajaPeroNoALaDestruccion() {
        for (String role : new String[] {Roles.AUDITOR, Roles.COMPLIANCE, Roles.OFICIAL_SEGURIDAD, Roles.SOPORTE}) {
            UUID active = tenant("ACTIVE", "u", role);
            UUID pending = tenant("PENDING_DELETION", "u", role);
            UUID deleted = tenant("DELETED", "u", role);
            assertTrue(source.hasRole(active.toString(), "u", role), role);
            assertTrue(source.hasRole(pending.toString(), "u", role), role);
            assertFalse(source.hasRole(deleted.toString(), "u", role), role);
        }
    }

    @Test
    void tenantInexistenteSeDeniega() {
        UUID t = UUID.randomUUID();
        jdbc.update("insert into role_assignment (id, tenant_id, user_id, role) values (?, ?, ?, ?)",
            UUID.randomUUID(), t, "u", Roles.AUDITOR);
        assertFalse(source.hasRole(t.toString(), "u", Roles.AUDITOR));
    }
}
