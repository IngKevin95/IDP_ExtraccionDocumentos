package com.idp.extraction.store;

import com.idp.tenant.context.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC sobre el DataSource enrutado por tenant (silo). Toda operacion exige que el tenant del dato
 * coincida con el del contexto (fijado por el consumidor idempotente): una discrepancia lanza
 * {@link TenantIsolationException} y contabiliza {@code idp.tenant.isolation.violation} para alerta.
 */
public final class JdbcExtractionRepository implements ExtractionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcExtractionRepository.class);
    public static final String VIOLATION_METRIC = "idp.tenant.isolation.violation";

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final String jsonCast;

    /** @param jsonbColumns true en PostgreSQL (cast a jsonb); false en motores de prueba sin jsonb */
    public JdbcExtractionRepository(JdbcTemplate jdbc, MeterRegistry meters, boolean jsonbColumns) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.jsonCast = jsonbColumns ? "cast(? as jsonb)" : "?";
    }

    @Override
    public boolean existsFinished(UUID documentId) {
        Integer n = jdbc.queryForObject(
            "select count(*) from extraction where document_id = ? and status <> ?", Integer.class,
            documentId, Status.ABORTED_INJECTION);
        return n != null && n > 0;
    }

    @Override
    public void save(ExtractionRecord e, List<FieldRecord> fields) {
        assertTenant(e.tenantId());
        jdbc.update("insert into extraction (id, document_id, tenant_id, status, typology_code, typology_version, "
                + "model_version, prompt_version, overall_score, tokens_in, tokens_out, cost_usd, review_task_id, "
                + "detail, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.id(), e.documentId(), e.tenantId(), e.status(), e.typologyCode(), e.typologyVersion(),
            e.modelVersion(), e.promptVersion(), e.overallScore(), e.tokensIn(), e.tokensOut(), e.costUsd(),
            e.reviewTaskId(), e.detail(), Timestamp.from(e.createdAt()));
        for (FieldRecord f : fields) {
            jdbc.update("insert into field_value (id, extraction_id, table_name, row_index, field_name, value_text, "
                    + "confidence_score, evidence_page, evidence_quote, bounding_box, requires_review, "
                    + "validation_error) values (?, ?, ?, ?, ?, ?, ?, ?, ?, " + jsonCast + ", ?, ?)",
                f.id(), e.id(), f.tableName(), f.rowIndex(), f.fieldName(), f.valueText(), f.confidence(),
                f.evidencePage(), f.evidenceQuote(), f.boundingBoxJson(), f.requiresReview(), f.validationError());
        }
    }

    @Override
    public void saveAiRecord(UUID tenantId, AiRecord r) {
        assertTenant(tenantId);
        jdbc.update("insert into ai_execution_record (id, extraction_id, payload, signature, key_id) "
            + "values (?, ?, ?, ?, ?)", r.id(), r.extractionId(), r.payload(), r.signature(), r.keyId());
    }

    @Override
    public Optional<ExtractionRecord> findByDocument(UUID tenantId, UUID documentId) {
        assertTenant(tenantId);
        List<ExtractionRecord> rows = jdbc.query(
            "select * from extraction where tenant_id = ? and document_id = ? order by created_at desc limit 1",
            (rs, i) -> new ExtractionRecord(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getString("status"), rs.getString("typology_code"),
                (Integer) rs.getObject("typology_version"), rs.getString("model_version"),
                rs.getString("prompt_version"), rs.getBigDecimal("overall_score"), rs.getInt("tokens_in"),
                rs.getInt("tokens_out"), rs.getBigDecimal("cost_usd"), rs.getObject("review_task_id", UUID.class),
                rs.getString("detail"), rs.getTimestamp("created_at").toInstant()),
            tenantId, documentId);
        return rows.stream().findFirst();
    }

    @Override
    public List<FieldRecord> fields(UUID tenantId, UUID extractionId) {
        assertTenant(tenantId);
        return jdbc.query("select f.* from field_value f join extraction e on e.id = f.extraction_id "
                + "where e.tenant_id = ? and f.extraction_id = ? order by f.table_name, f.row_index, f.field_name",
            (rs, i) -> new FieldRecord(rs.getObject("id", UUID.class), rs.getString("table_name"),
                (Integer) rs.getObject("row_index"), rs.getString("field_name"), rs.getString("value_text"),
                rs.getBigDecimal("confidence_score"), (Integer) rs.getObject("evidence_page"),
                rs.getString("evidence_quote"), rs.getString("bounding_box"), rs.getBoolean("requires_review"),
                rs.getString("validation_error")),
            tenantId, extractionId);
    }

    @Override
    public Optional<AiRecord> aiRecord(UUID tenantId, UUID extractionId) {
        assertTenant(tenantId);
        return jdbc.query("select a.* from ai_execution_record a join extraction e on e.id = a.extraction_id "
                + "where e.tenant_id = ? and a.extraction_id = ?",
            (rs, i) -> new AiRecord(rs.getObject("id", UUID.class), rs.getObject("extraction_id", UUID.class),
                rs.getString("payload"), rs.getString("signature"), rs.getString("key_id")),
            tenantId, extractionId).stream().findFirst();
    }

    private void assertTenant(UUID tenantId) {
        String ctx = TenantContextHolder.getTenantId();
        if (ctx == null || !ctx.equals(tenantId.toString())) {
            meters.counter(VIOLATION_METRIC).increment();
            LOG.error("Violacion de aislamiento de tenant detectada en persistencia de extraccion");
            throw new TenantIsolationException("El tenant del dato no coincide con el contexto del silo");
        }
    }
}
