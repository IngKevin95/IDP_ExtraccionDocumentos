package com.idp.tenant.infrastructure.persistence;

import com.idp.tenant.domain.LegalHold;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LegalHoldRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<LegalHold> MAPPER = (rs, i) -> new LegalHold(
            Db.uuid(rs, "id"), Db.uuid(rs, "tenant_id"), rs.getString("reason"), rs.getString("applied_by"),
            Db.instant(rs, "created_at"), rs.getString("released_by"), Db.instant(rs, "released_at"));

    public LegalHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LegalHold h) {
        jdbc.sql("INSERT INTO legal_hold_records(id, tenant_id, document_id, reason, applied_by, status, created_at) "
                        + "VALUES (:id, :t, NULL, :r, :b, 'ACTIVE', :a)")
                .param("id", h.id()).param("t", h.tenantId()).param("r", h.reasonCode())
                .param("b", h.appliedBy()).param("a", Db.odt(h.appliedAt())).update();
    }

    public List<LegalHold> findActive(UUID tenantId) {
        return jdbc.sql("SELECT * FROM legal_hold_records WHERE tenant_id = :t AND document_id IS NULL "
                        + "AND status = 'ACTIVE' ORDER BY created_at")
                .param("t", tenantId).query(MAPPER).list();
    }

    public void release(UUID id, String by, Instant now) {
        jdbc.sql("UPDATE legal_hold_records SET status = 'RELEASED', released_by = :b, released_at = :a "
                        + "WHERE id = :id")
                .param("b", by).param("a", Db.odt(now)).param("id", id).update();
    }
}
