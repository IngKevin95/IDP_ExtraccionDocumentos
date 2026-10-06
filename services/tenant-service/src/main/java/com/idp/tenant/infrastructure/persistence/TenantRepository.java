package com.idp.tenant.infrastructure.persistence;

import com.idp.tenant.domain.Plan;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantConfig;
import com.idp.tenant.domain.TenantStatus;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<Tenant> TENANT = (rs, i) -> new Tenant(
            Db.uuid(rs, "id"), rs.getString("name"), TenantStatus.valueOf(rs.getString("status")),
            Db.uuid(rs, "plan_id"), Db.instant(rs, "deletion_due_at"), Db.instant(rs, "created_at"),
            Db.instant(rs, "updated_at"));

    private static final RowMapper<TenantConfig> CONFIG = (rs, i) -> new TenantConfig(
            Db.uuid(rs, "tenant_id"), rs.getString("data_kek_id"), rs.getString("audit_kek_id"),
            JsonSupport.readMap(rs.getString("settings_text")));

    private static final RowMapper<Plan> PLAN = (rs, i) -> new Plan(
            Db.uuid(rs, "id"), rs.getString("name"), JsonSupport.readMap(rs.getString("features_text")),
            JsonSupport.readLongMap(rs.getString("limits_text")));

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Plan> findPlan(UUID id) {
        return jdbc.sql("SELECT id, name, CAST(features AS VARCHAR) AS features_text, "
                        + "CAST(quota_limits AS VARCHAR) AS limits_text FROM planes WHERE id = :id")
                .param("id", id).query(PLAN).optional();
    }

    public void insert(Tenant t) {
        jdbc.sql("INSERT INTO tenants(id, name, status, plan_id, deletion_due_at, created_at, updated_at) "
                        + "VALUES (:id, :name, :status, :plan, :due, :c, :u)")
                .param("id", t.id()).param("name", t.name()).param("status", t.status().name())
                .param("plan", t.planId()).param("due", Db.odt(t.deletionDueAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("c", Db.odt(t.createdAt())).param("u", Db.odt(t.updatedAt())).update();
    }

    public Optional<Tenant> find(UUID id) {
        return jdbc.sql("SELECT * FROM tenants WHERE id = :id").param("id", id).query(TENANT).optional();
    }

    public Optional<Tenant> findForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM tenants WHERE id = :id FOR UPDATE").param("id", id).query(TENANT).optional();
    }

    public List<Tenant> list() {
        return jdbc.sql("SELECT * FROM tenants ORDER BY created_at, id").query(TENANT).list();
    }

    public void updateStatus(UUID id, TenantStatus status, Instant deletionDueAt, Instant now) {
        jdbc.sql("UPDATE tenants SET status = :s, deletion_due_at = :due, updated_at = :u WHERE id = :id")
                .param("s", status.name()).param("due", Db.odt(deletionDueAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("u", Db.odt(now)).param("id", id).update();
    }

    public void updatePlan(UUID id, UUID planId, Instant now) {
        jdbc.sql("UPDATE tenants SET plan_id = :p, updated_at = :u WHERE id = :id")
                .param("p", planId).param("u", Db.odt(now)).param("id", id).update();
    }

    public List<Tenant> findPendingDeletionDue(Instant now) {
        return jdbc.sql("SELECT * FROM tenants WHERE status = 'PENDING_DELETION' AND deletion_due_at <= :now "
                        + "ORDER BY deletion_due_at")
                .param("now", Db.odt(now)).query(TENANT).list();
    }

    public void insertConfig(TenantConfig c) {
        jdbc.sql("INSERT INTO tenant_config(tenant_id, data_kek_id, audit_kek_id, settings) "
                        + "VALUES (:t, :d, :a, CAST(:s AS JSONB))")
                .param("t", c.tenantId()).param("d", c.dataKekId()).param("a", c.auditKekId())
                .param("s", JsonSupport.write(c.settings())).update();
    }

    public Optional<TenantConfig> findConfig(UUID tenantId) {
        return jdbc.sql("SELECT tenant_id, data_kek_id, audit_kek_id, "
                        + "CAST(settings AS VARCHAR) AS settings_text FROM tenant_config WHERE tenant_id = :t")
                .param("t", tenantId).query(CONFIG).optional();
    }

    public void updateKeks(UUID tenantId, String dataKekId, String auditKekId) {
        jdbc.sql("UPDATE tenant_config SET data_kek_id = :d, audit_kek_id = :a WHERE tenant_id = :t")
                .param("d", dataKekId).param("a", auditKekId).param("t", tenantId).update();
    }

    public void updateSettings(UUID tenantId, Map<String, Object> settings) {
        jdbc.sql("UPDATE tenant_config SET settings = CAST(:s AS JSONB) WHERE tenant_id = :t")
                .param("s", JsonSupport.write(settings)).param("t", tenantId).update();
    }

    public void insertSilo(UUID tenantId, String dbName, String bucketName, String openBaoRole) {
        jdbc.sql("INSERT INTO silo_location(tenant_id, db_name, bucket_name, openbao_role) VALUES (:t, :d, :b, :r)")
                .param("t", tenantId).param("d", dbName).param("b", bucketName).param("r", openBaoRole).update();
    }

    public Optional<Map<String, Object>> findSilo(UUID tenantId) {
        return jdbc.sql("SELECT db_name, bucket_name, openbao_role FROM silo_location WHERE tenant_id = :t")
                .param("t", tenantId).query((rs, i) -> Map.<String, Object>of(
                        "dbName", rs.getString("db_name"), "bucketName", rs.getString("bucket_name"),
                        "openBaoRole", rs.getString("openbao_role"))).optional();
    }

    public void insertStep(UUID tenantId, String step, String action, String status, String detail, Instant now) {
        jdbc.sql("INSERT INTO provisioning_step(id, tenant_id, step, action, status, detail, created_at) "
                        + "VALUES (:id, :t, :s, :a, :st, :d, :c)")
                .param("id", UUID.randomUUID()).param("t", tenantId).param("s", step).param("a", action)
                .param("st", status)
                .param("d", detail == null ? null : detail.substring(0, Math.min(500, detail.length())))
                .param("c", Db.odt(now)).update();
    }

    public List<String> findSteps(UUID tenantId) {
        return jdbc.sql("SELECT step, action, status FROM provisioning_step WHERE tenant_id = :t "
                        + "ORDER BY seq")
                .param("t", tenantId)
                .query((rs, i) -> rs.getString("action") + ":" + rs.getString("step") + ":" + rs.getString("status"))
                .list();
    }
}
