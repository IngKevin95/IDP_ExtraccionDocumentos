package com.idp.tenant.infrastructure.persistence;

import com.idp.tenant.domain.AccessCertification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CertificationRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<AccessCertification> MAPPER = (rs, i) -> new AccessCertification(
            Db.uuid(rs, "id"), Db.uuid(rs, "tenant_id"), rs.getInt("period_year"), rs.getInt("period_quarter"),
            rs.getString("generated_by"), Db.instant(rs, "generated_at"), rs.getString("report_json"),
            rs.getString("report_sha256"), rs.getString("signature"), rs.getString("key_id"));

    public CertificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AccessCertification c) {
        jdbc.sql("INSERT INTO access_certification(id, tenant_id, period_year, period_quarter, generated_by, "
                        + "generated_at, report_json, report_sha256, signature, key_id) "
                        + "VALUES (:id, :t, :y, :q, :g, :at, :r, :h, :s, :k)")
                .param("id", c.id()).param("t", c.tenantId()).param("y", c.year()).param("q", c.quarter())
                .param("g", c.generatedBy()).param("at", Db.odt(c.generatedAt())).param("r", c.reportJson())
                .param("h", c.reportSha256()).param("s", c.signature()).param("k", c.keyId()).update();
    }

    public Optional<AccessCertification> find(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM access_certification WHERE id = :id AND tenant_id = :t")
                .param("id", id).param("t", tenantId).query(MAPPER).optional();
    }
}
