package com.idp.tenant.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** approval_request: solicitudes pendientes de una segunda aprobacion. */
@Repository
public class ApprovalRepository {
    public record Pending(UUID id, String requestedBy) {}

    private final JdbcClient jdbc;

    public ApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Pending> findPending(UUID tenantId, String action, String target, Instant notBefore) {
        return jdbc.sql("SELECT id, requested_by FROM approval_request WHERE tenant_id = :t AND action = :a "
                        + "AND target = :g AND status = 'PENDING' AND requested_at > :nb ORDER BY requested_at")
                .param("t", tenantId).param("a", action).param("g", target).param("nb", Db.odt(notBefore))
                .query((rs, i) -> new Pending(Db.uuid(rs, "id"), rs.getString("requested_by"))).list()
                .stream().findFirst();
    }

    public void insert(UUID tenantId, String action, String target, String requestedBy, Instant now) {
        jdbc.sql("INSERT INTO approval_request(id, tenant_id, action, target, requested_by, requested_at, status) "
                        + "VALUES (:id, :t, :a, :g, :r, :n, 'PENDING')")
                .param("id", UUID.randomUUID()).param("t", tenantId).param("a", action).param("g", target)
                .param("r", requestedBy).param("n", Db.odt(now)).update();
    }

    public void approve(UUID id, String approvedBy, Instant now) {
        jdbc.sql("UPDATE approval_request SET status = 'APPROVED', approved_by = :b, approved_at = :n WHERE id = :id")
                .param("b", approvedBy).param("n", Db.odt(now)).param("id", id).update();
    }
}
