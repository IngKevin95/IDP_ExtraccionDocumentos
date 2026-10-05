package com.idp.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consulta sincrona de {@code role_assignment} en la base de control (SEC-002). La asignacion
 * vigente no esta borrada logicamente ni vencida, y su tenant esta ACTIVE. Los roles de supervision
 * (AUDITOR, COMPLIANCE, OFICIAL_SEGURIDAD y break-glass) siguen vigentes mientras el tenant no este DELETED
 * (RN-14: expedientes retenidos o en baja).
 */
public final class JdbcRoleAssignmentSource implements RoleAssignmentSource {

    /** Roles que sobreviven a la baja del tenant mientras no se destruya (RN-14). */
    static final java.util.Set<String> RETAINED_ROLES = java.util.Set.of(Roles.AUDITOR, Roles.COMPLIANCE,
        Roles.OFICIAL_SEGURIDAD, Roles.SOPORTE);

    public static boolean isRetained(String role) {
        return RETAINED_ROLES.contains(role);
    }

    private static final String SQL = "select count(*) from role_assignment ra join tenants t on t.id = ra.tenant_id "
        + "where ra.tenant_id = ? and ra.user_id = ? and ra.role = ? and ra.deleted_at is null "
        + "and (ra.expires_at is null or ra.expires_at > now()) "
        + "and (t.status = 'ACTIVE' or (t.status <> 'DELETED' and ? = 1))";

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
        int retained = isRetained(role) ? 1 : 0;
        Integer count = controlDb.queryForObject(SQL, Integer.class, tenant, userId, role, retained);
        return count != null && count > 0;
    }
}
