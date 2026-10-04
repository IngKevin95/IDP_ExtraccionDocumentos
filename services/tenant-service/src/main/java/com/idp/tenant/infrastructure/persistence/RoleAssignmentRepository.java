package com.idp.tenant.infrastructure.persistence;

import com.idp.tenant.domain.RoleAssignment;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** role_assignment con borrado logico: una asignacion activa tiene deleted_at nulo y no ha expirado. */
@Repository
public class RoleAssignmentRepository {
    private static final String ACTIVE = "deleted_at IS NULL AND (expires_at IS NULL OR expires_at > :now)";

    private final JdbcClient jdbc;

    private static final RowMapper<RoleAssignment> MAPPER = (rs, i) -> new RoleAssignment(
            Db.uuid(rs, "id"), Db.uuid(rs, "tenant_id"), rs.getString("user_id"), rs.getString("role"),
            rs.getString("granted_by"), rs.getString("approved_by"), Db.instant(rs, "expires_at"),
            Db.instant(rs, "created_at"), Db.instant(rs, "deleted_at"), rs.getString("deleted_by"),
            rs.getString("delete_reason"), rs.getString("justification"));

    public RoleAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RoleAssignment r) {
        jdbc.sql("INSERT INTO role_assignment(id, tenant_id, user_id, role, granted_by, approved_by, expires_at, "
                        + "created_at, justification) VALUES (:id, :t, :u, :r, :g, :a, :e, :c, :j)")
                .param("id", r.id()).param("t", r.tenantId()).param("u", r.userId()).param("r", r.role())
                .param("g", r.grantedBy()).param("a", r.approvedBy())
                .param("e", Db.odt(r.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("c", Db.odt(r.createdAt())).param("j", r.justification()).update();
    }

    public boolean hasActiveRole(UUID tenantId, String userId, String role, Instant now) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM role_assignment WHERE tenant_id = :t AND user_id = :u "
                        + "AND role = :r AND " + ACTIVE)
                .param("t", tenantId).param("u", userId).param("r", role).param("now", Db.odt(now))
                .query(Integer.class).single();
        return n != null && n > 0;
    }

    public List<RoleAssignment> findActiveByTenant(UUID tenantId, Instant now) {
        return jdbc.sql("SELECT * FROM role_assignment WHERE tenant_id = :t AND " + ACTIVE
                        + " ORDER BY user_id, role")
                .param("t", tenantId).param("now", Db.odt(now)).query(MAPPER).list();
    }

    public List<RoleAssignment> findActiveByUser(UUID tenantId, String userId, Instant now) {
        return jdbc.sql("SELECT * FROM role_assignment WHERE tenant_id = :t AND user_id = :u AND " + ACTIVE)
                .param("t", tenantId).param("u", userId).param("now", Db.odt(now)).query(MAPPER).list();
    }

    public List<RoleAssignment> findAllByTenant(UUID tenantId) {
        return jdbc.sql("SELECT * FROM role_assignment WHERE tenant_id = :t ORDER BY user_id, role, created_at")
                .param("t", tenantId).query(MAPPER).list();
    }

    public List<RoleAssignment> findExpiredActiveByRole(String role, Instant now) {
        return jdbc.sql("SELECT * FROM role_assignment WHERE role = :r AND deleted_at IS NULL "
                        + "AND expires_at IS NOT NULL AND expires_at <= :now")
                .param("r", role).param("now", Db.odt(now)).query(MAPPER).list();
    }

    public void softDelete(UUID id, String by, String reason, Instant now) {
        jdbc.sql("UPDATE role_assignment SET deleted_at = :d, deleted_by = :b, delete_reason = :r "
                        + "WHERE id = :id AND deleted_at IS NULL")
                .param("d", Db.odt(now)).param("b", by).param("r", reason).param("id", id).update();
    }
}
