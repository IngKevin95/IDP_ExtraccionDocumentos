package com.idp.tenant.infrastructure.persistence;

import com.idp.tenant.domain.QuotaPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<QuotaPeriod> MAPPER = (rs, i) -> new QuotaPeriod(
            Db.uuid(rs, "id"), Db.uuid(rs, "tenant_id"), rs.getString("metric_name"),
            rs.getObject("period_start", LocalDate.class), rs.getLong("limit_value"), rs.getLong("consumed"),
            rs.getBoolean("threshold_notified"));

    public QuotaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<QuotaPeriod> find(UUID tenantId, String metric, LocalDate periodStart) {
        return jdbc.sql("SELECT * FROM quota_period WHERE tenant_id = :t AND metric_name = :m "
                        + "AND period_start = :p")
                .param("t", tenantId).param("m", metric).param("p", periodStart).query(MAPPER).optional();
    }

    public void insert(UUID tenantId, String metric, LocalDate periodStart, long limit) {
        jdbc.sql("INSERT INTO quota_period(id, tenant_id, metric_name, period_start, limit_value, consumed, "
                        + "threshold_notified) VALUES (:id, :t, :m, :p, :l, 0, FALSE)")
                .param("id", UUID.randomUUID()).param("t", tenantId).param("m", metric)
                .param("p", periodStart).param("l", limit).update();
    }

    public void updateLimit(UUID id, long limit) {
        jdbc.sql("UPDATE quota_period SET limit_value = :l WHERE id = :id").param("l", limit).param("id", id).update();
    }

    public void addConsumption(UUID id, long value) {
        jdbc.sql("UPDATE quota_period SET consumed = consumed + :v WHERE id = :id")
                .param("v", value).param("id", id).update();
    }

    /** Marca el umbral como notificado de forma atomica; true solo para quien lo cruza primero. */
    public boolean markThresholdNotified(UUID id, int percent) {
        return jdbc.sql("UPDATE quota_period SET threshold_notified = TRUE WHERE id = :id "
                        + "AND threshold_notified = FALSE AND consumed * 100 > limit_value * :p")
                .param("id", id).param("p", percent).update() == 1;
    }

    public void insertConsumption(UUID tenantId, String metric, long value, Instant now) {
        jdbc.sql("INSERT INTO consumption_record(id, tenant_id, metric_name, amount, recorded_at) "
                        + "VALUES (:id, :t, :m, :v, :r)")
                .param("id", UUID.randomUUID()).param("t", tenantId).param("m", metric).param("v", value)
                .param("r", Db.odt(now)).update();
    }
}
