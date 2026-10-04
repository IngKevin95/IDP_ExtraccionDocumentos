package com.idp.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consulta sincrona de {@code role_assignment} en la base de control (SEC-002). La asignacion
 * vigente no esta borrada logicamente ni vencida.
 */
public final class JdbcRoleAssignmentSource implements RoleAssignmentSource {

    private static final String SQL = "select count(*) from role_assignment where tenant_id = ? "
        + "and user_id = ? and role = ? and deleted_at is null and (expires_at is null or expires_at > now())";

    private final JdbcTemplate controlDb;

    public JdbcRoleAssignmentSource(JdbcTemplate controlDb) {
        this.controlDb = controlDb;
    }

    @Override
    public boolean hasRole(String tenantId, String userId, String role) {
        if (tenantId == null) {
            return false;
        }
        UUID tenant;
        try {
            tenant = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Integer count = controlDb.queryForObject(SQL, Integer.class, tenant, userId, role);
        return count != null && count > 0;
    }
}
