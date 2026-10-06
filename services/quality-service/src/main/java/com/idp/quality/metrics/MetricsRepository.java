package com.idp.quality.metrics;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso JDBC a los agregados de calidad. Solo contadores y metadatos: nunca valores de campos (SEC-050). */
@Repository
public class MetricsRepository {

    /** Totales de un dia para una tipologia o para todas. */
    public record DailyRow(LocalDate fecha, int total, int stp, int hitl, int silentErrors, int blindSamples) {
    }

    public record FieldErrorRow(String tipologia, String campo, String tipoCorreccion, String origen, int cuenta) {
    }

    private final JdbcTemplate jdbc;

    public MetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void addDaily(UUID tenant, LocalDate fecha, String tipologia, int total, int stp, int hitl, int silent,
                         int blind) {
        // Portable (PostgreSQL y H2): sin ON CONFLICT. El insert condicional no falla ante repeticion.
        jdbc.update("insert into qa_metrics_daily (tenant_id, fecha, tipologia) select cast(? as uuid), "
            + "cast(? as date), cast(? as varchar) where not exists (select 1 from qa_metrics_daily "
            + "where tenant_id = cast(? as uuid) and fecha = cast(? as date) and tipologia = cast(? as varchar))",
            tenant, fecha, tipologia, tenant, fecha, tipologia);
        jdbc.update("update qa_metrics_daily set total_documentos = total_documentos + ?, "
            + "stp_count = stp_count + ?, hitl_count = hitl_count + ?, silent_error_count = silent_error_count + ?, "
            + "blind_samples_total = blind_samples_total + ?, updated_at = now() "
            + "where tenant_id = ? and fecha = ? and tipologia = ?",
            total, stp, hitl, silent, blind, tenant, fecha, tipologia);
    }

    public void addFieldError(UUID tenant, LocalDate fecha, String tipologia, String campo, String tipo,
                              String origen) {
        jdbc.update("insert into qa_field_error (tenant_id, fecha, tipologia, campo_modificado, tipo_correccion, "
            + "origen) select cast(? as uuid), cast(? as date), cast(? as varchar), cast(? as varchar), "
            + "cast(? as varchar), cast(? as varchar) where not exists (select 1 from qa_field_error "
            + "where tenant_id = cast(? as uuid) and fecha = cast(? as date) and tipologia = cast(? as varchar) "
            + "and campo_modificado = cast(? as varchar) and tipo_correccion = cast(? as varchar) "
            + "and origen = cast(? as varchar))",
            tenant, fecha, tipologia, campo, tipo, origen, tenant, fecha, tipologia, campo, tipo, origen);
        jdbc.update("update qa_field_error set cuenta = cuenta + 1 where tenant_id = ? and fecha = ? "
            + "and tipologia = ? and campo_modificado = ? and tipo_correccion = ? and origen = ?",
            tenant, fecha, tipologia, campo, tipo, origen);
    }

    public void addExtractionSample(UUID tenant, LocalDate fecha, String tipologia, String modelPromptKey,
                                    Integer latencyMs, Long costMicros) {
        jdbc.update("insert into qa_extraction_sample (id, tenant_id, fecha, tipologia, model_prompt_key, "
            + "latency_ms, cost_micros) values (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), tenant, fecha, tipologia, modelPromptKey, latencyMs, costMicros);
    }

    /** Registra la seleccion para revision ciega; false si el oficio ya estaba seleccionado. */
    public boolean selectBlind(UUID tenant, UUID documentId, String tipologia, UUID sampleId) {
        return jdbc.update("insert into qa_blind_sample (tenant_id, document_id, tipologia, sample_id) "
            + "select cast(? as uuid), cast(? as uuid), cast(? as varchar), cast(? as uuid) where not exists "
            + "(select 1 from qa_blind_sample where tenant_id = cast(? as uuid) and document_id = cast(? as uuid))",
            tenant, documentId, tipologia, sampleId, tenant, documentId) > 0;
    }

    /** Si el oficio estaba pendiente de revision ciega lo marca revisado y devuelve true. */
    public boolean consumeBlind(UUID tenant, UUID documentId) {
        return jdbc.update("update qa_blind_sample set status = 'REVIEWED' where tenant_id = ? and document_id = ? "
            + "and status = 'PENDING'", tenant, documentId) > 0;
    }

    /** Conteos agregados de la cola ciega: sin identificadores (SEC-051). */
    public record BlindSummary(int pending, int reviewed, int pendingUnderDay, int pendingUnderWeek,
                               int pendingOverWeek) {
    }

    public BlindSummary blindSummary(UUID tenant, java.time.Instant now) {
        java.sql.Timestamp day = java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(1)));
        java.sql.Timestamp week = java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(7)));
        String base = "select count(*) from qa_blind_sample where tenant_id = ? and status = ?";
        int pending = count(base, tenant, "PENDING");
        int reviewed = count(base, tenant, "REVIEWED");
        int underDay = count(base + " and selected_at >= ?", tenant, "PENDING", day);
        int underWeek = count(base + " and selected_at >= ? and selected_at < ?", tenant, "PENDING", week, day);
        return new BlindSummary(pending, reviewed, underDay, underWeek, pending - underDay - underWeek);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Totales por dia (todas las tipologias o solo la indicada), ordenados por fecha. */
    public List<DailyRow> daily(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        StringBuilder sql = new StringBuilder("select fecha, sum(total_documentos), sum(stp_count), "
            + "sum(hitl_count), sum(silent_error_count), sum(blind_samples_total) from qa_metrics_daily "
            + "where tenant_id = ? and fecha between ? and ?");
        List<Object> args = new java.util.ArrayList<>(List.of(tenant, from, to));
        if (tipologia != null) {
            sql.append(" and tipologia = ?");
            args.add(tipologia);
        }
        sql.append(" group by fecha order by fecha");
        return jdbc.query(sql.toString(), (rs, i) -> new DailyRow(rs.getDate(1).toLocalDate(), rs.getInt(2),
            rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)), args.toArray());
    }

    public List<FieldErrorRow> fieldErrors(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        StringBuilder sql = new StringBuilder("select tipologia, campo_modificado, tipo_correccion, origen, "
            + "sum(cuenta) from qa_field_error where tenant_id = ? and fecha between ? and ?");
        List<Object> args = new java.util.ArrayList<>(List.of(tenant, from, to));
        if (tipologia != null) {
            sql.append(" and tipologia = ?");
            args.add(tipologia);
        }
        sql.append(" group by tipologia, campo_modificado, tipo_correccion, origen "
            + "order by tipologia, campo_modificado, tipo_correccion, origen");
        return jdbc.query(sql.toString(), (rs, i) -> new FieldErrorRow(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getInt(5)), args.toArray());
    }

    /** Latencias (ms) de las extracciones del rango; las filas sin dato se omiten. */
    public long[] latencies(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        return samples("latency_ms", tenant, from, to, tipologia);
    }

    public long[] costs(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        return samples("cost_micros", tenant, from, to, tipologia);
    }

    private long[] samples(String column, UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        StringBuilder sql = new StringBuilder("select " + column + " from qa_extraction_sample where tenant_id = ? "
            + "and fecha between ? and ? and " + column + " is not null");
        List<Object> args = new java.util.ArrayList<>(List.of(tenant, from, to));
        if (tipologia != null) {
            sql.append(" and tipologia = ?");
            args.add(tipologia);
        }
        return jdbc.query(sql.toString(), (rs, i) -> rs.getLong(1), args.toArray()).stream()
            .mapToLong(Long::longValue).toArray();
    }

    public List<UUID> tenantsWithMetrics(LocalDate from, LocalDate to) {
        return jdbc.query("select distinct tenant_id from qa_metrics_daily where fecha between ? and ?",
            (rs, i) -> rs.getObject(1, UUID.class), from, to);
    }

    public void saveDriftAlert(UUID tenant, LocalDate fecha, String kind, double observed, double baseline) {
        jdbc.update("delete from qa_drift_alert where tenant_id = ? and fecha = ? and kind = ?", tenant, fecha, kind);
        jdbc.update("insert into qa_drift_alert (tenant_id, fecha, kind, observed, baseline) values (?, ?, ?, ?, ?)",
            tenant, fecha, kind, observed, baseline);
    }

    public int countDriftAlerts(UUID tenant, LocalDate fecha) {
        Integer n = jdbc.queryForObject("select count(*) from qa_drift_alert where tenant_id = ? and fecha = ?",
            Integer.class, tenant, fecha);
        return n == null ? 0 : n;
    }
}
