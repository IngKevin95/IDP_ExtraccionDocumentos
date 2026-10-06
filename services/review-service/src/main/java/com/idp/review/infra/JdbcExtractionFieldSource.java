package com.idp.review.infra;

import com.idp.review.domain.FieldCandidate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lee los campos con requires_review del extraction-service en el silo del tenant (tablas extraction y field_value). Se
 * ejecuta en una transaccion propia: si el silo no tiene esas tablas, el fallo no aborta la transaccion del consumidor
 * y la tarea se crea sin campos.
 */
public class JdbcExtractionFieldSource implements FieldCandidateSource {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcExtractionFieldSource.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate isolated;

    public JdbcExtractionFieldSource(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolated.setReadOnly(true);
    }

    private static final String FIELD_SELECT = "select f.table_name, f.row_index, f.field_name, "
            + "f.value_text, f.confidence_score, f.evidence_page, cast(f.bounding_box as varchar(200)) as bbox "
            + "from field_value f join extraction e on e.id = f.extraction_id ";
    private static final String FIELD_ORDER = " order by f.table_name, f.row_index, f.field_name";

    private static FieldCandidate candidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        String table = rs.getString("table_name");
        int row = rs.getInt("row_index");
        boolean hasRow = !rs.wasNull();
        String name = table == null ? rs.getString("field_name")
                : table + (hasRow ? "[" + row + "]" : "") + "." + rs.getString("field_name");
        int page = rs.getInt("evidence_page");
        Integer pageOrNull = rs.wasNull() ? null : page;
        return new FieldCandidate(name, pageOrNull, rs.getString("bbox"), rs.getString("value_text"),
                rs.getBigDecimal("confidence_score"));
    }

    @Override
    public List<FieldCandidate> candidates(UUID documentId, UUID taskId) {
        try {
            return isolated.execute(status -> jdbc.query(FIELD_SELECT
                    + "where e.review_task_id = ? and e.document_id = ? and f.requires_review = true" + FIELD_ORDER,
                    (rs, i) -> candidate(rs), taskId, documentId));
        } catch (DataAccessException e) {
            LOG.warn("No se pudieron leer los campos dudosos de la tarea {}: {}", taskId,
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Todos los campos de la extraccion mas reciente del documento (revision ciega). */
    @Override
    public List<FieldCandidate> approvedFields(UUID documentId) {
        try {
            return isolated.execute(status -> jdbc.query(FIELD_SELECT + "where e.id = (select x.id from extraction x "
                    + "where x.document_id = ? order by x.created_at desc limit 1)" + FIELD_ORDER,
                    (rs, i) -> candidate(rs), documentId));
        } catch (DataAccessException e) {
            LOG.warn("No se pudieron leer los campos del documento aprobado: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public java.util.Optional<String> uploader(UUID documentId) {
        try {
            return isolated.execute(status -> jdbc.query("select uploaded_by from document where id = ?",
                    (rs, i) -> rs.getString(1), documentId).stream().filter(s -> s != null && !s.isBlank())
                    .findFirst());
        } catch (DataAccessException e) {
            return java.util.Optional.empty();
        }
    }
}
