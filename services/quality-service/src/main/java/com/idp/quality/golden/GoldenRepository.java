package com.idp.quality.golden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.quality.golden.EvaluationResult.ThresholdRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia del golden set (solo sintetico), corridas, calibracion y umbrales. Todo por tenant. */
@Repository
public class GoldenRepository {

    public record EvaluationRow(UUID id, String kind, String status, OffsetDateTime fecha, String versionPrompt,
                                Double accuracy, Double precision, Double recall, Double f1, Double eceRaw,
                                Double eceCalibrated, Integer samples, String error) {
    }

    private static final TypeReference<Map<String, String>> TRUTH = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GoldenRepository(JdbcTemplate jdbc, ObjectMapper idpObjectMapper) {
        this.jdbc = jdbc;
        this.mapper = idpObjectMapper;
    }

    /** Inserta o reemplaza (por external_id) un oficio sintetico; devuelve su id. */
    public UUID save(UUID tenant, String externalId, String nombre, String tipologia, List<String> tags,
                     Map<String, String> verdad) {
        if (externalId != null) {
            jdbc.update("delete from golden_set_document where tenant_id = ? and external_id = ?", tenant,
                externalId);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into golden_set_document (id, tenant_id, external_id, nombre, tipologia, tags, "
            + "payload_sintetico_json) values (?, ?, ?, ?, ?, ?, ?)", id, tenant, externalId, nombre, tipologia,
            String.join(",", tags), json(verdad));
        return id;
    }

    public List<GoldenDocument> list(UUID tenant) {
        return jdbc.query("select id, external_id, nombre, tipologia, tags, payload_sintetico_json "
            + "from golden_set_document where tenant_id = ? order by created_at, id", (rs, i) -> doc(rs), tenant);
    }

    public Optional<GoldenDocument> find(UUID tenant, UUID id) {
        return jdbc.query("select id, external_id, nombre, tipologia, tags, payload_sintetico_json "
            + "from golden_set_document where tenant_id = ? and id = ?", (rs, i) -> doc(rs), tenant, id)
            .stream().findFirst();
    }

    public boolean delete(UUID tenant, UUID id) {
        return jdbc.update("delete from golden_set_document where tenant_id = ? and id = ?", tenant, id) > 0;
    }

    private GoldenDocument doc(java.sql.ResultSet rs) throws java.sql.SQLException {
        String tags = rs.getString(5);
        List<String> tagList = tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
        return new GoldenDocument(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            tagList, readTruth(rs.getString(6)));
    }

    public void createEvaluation(UUID id, UUID tenant, String kind, String versionPrompt) {
        jdbc.update("insert into golden_set_evaluation (id, tenant_id, kind, status, version_prompt) "
            + "values (?, ?, ?, 'QUEUED', ?)", id, tenant, kind, versionPrompt);
    }

    public void markRunning(UUID id) {
        jdbc.update("update golden_set_evaluation set status = 'RUNNING' where id = ?", id);
    }

    public void complete(UUID id, EvaluationResult r) {
        jdbc.update("update golden_set_evaluation set status = 'DONE', accuracy = ?, precision_micro = ?, "
            + "recall = ?, f1 = ?, ece_raw = ?, ece_calibrated = ?, samples = ?, result_json = ? where id = ?",
            r.accuracy(), r.precision(), r.recall(), r.f1(), r.eceRaw(), r.eceCalibrated(), r.samples(),
            json(r), id);
    }

    /** El replay guarda el reporte comparativo completo y las metricas del candidato. */
    public void completeReplay(UUID id, com.idp.quality.replay.ReplayService.Report report) {
        EvaluationResult c = report.candidate();
        jdbc.update("update golden_set_evaluation set status = 'DONE', accuracy = ?, precision_micro = ?, "
            + "recall = ?, f1 = ?, ece_raw = ?, ece_calibrated = ?, samples = ?, result_json = ? where id = ?",
            c.accuracy(), c.precision(), c.recall(), c.f1(), c.eceRaw(), c.eceCalibrated(), c.samples(),
            json(report), id);
    }

    public Optional<String> resultJson(UUID tenant, UUID id) {
        return jdbc.queryForList("select result_json from golden_set_evaluation where tenant_id = ? and id = ? "
            + "and result_json is not null", String.class, tenant, id).stream().findFirst();
    }

    public void fail(UUID id, String error) {
        jdbc.update("update golden_set_evaluation set status = 'FAILED', error = ? where id = ?",
            error == null ? null : error.substring(0, Math.min(300, error.length())), id);
    }

    public Optional<EvaluationRow> findEvaluation(UUID tenant, UUID id) {
        return jdbc.query(EVAL_SELECT + " where tenant_id = ? and id = ?", (rs, i) -> evalRow(rs), tenant, id)
            .stream().findFirst();
    }

    public Optional<EvaluationResult> latestResult(UUID tenant, String versionPrompt) {
        List<String> rows = jdbc.queryForList("select result_json from golden_set_evaluation where tenant_id = ? "
            + "and version_prompt = ? and kind = 'EVALUATION' and status = 'DONE' order by fecha desc, id "
            + "limit 1", String.class, tenant, versionPrompt);
        return rows.stream().findFirst().map(this::readResult);
    }

    public int countEvaluations(UUID tenant) {
        Integer n = jdbc.queryForObject("select count(*) from golden_set_evaluation where tenant_id = ?",
            Integer.class, tenant);
        return n == null ? 0 : n;
    }

    public void saveCalibration(UUID evaluationId, UUID tenant, String key, EvaluationResult.CalibrationModel m) {
        jdbc.update("insert into qa_calibration (evaluation_id, tenant_id, model_prompt_key, model_json) "
            + "values (?, ?, ?, ?)", evaluationId, tenant, key, json(m));
    }

    /** Reemplaza los umbrales vigentes del par modelo+prompt por los de la ultima evaluacion. */
    public void replaceThresholds(UUID tenant, String key, UUID evaluationId, List<ThresholdRow> rows) {
        jdbc.update("delete from qa_threshold where tenant_id = ? and model_prompt_key = ?", tenant, key);
        for (ThresholdRow r : rows) {
            jdbc.update("insert into qa_threshold (tenant_id, model_prompt_key, tipologia, campo, scope, tau_auto, "
                + "tau_revisar, target_precision, samples, attainable, evaluation_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tenant, key, r.tipologia(), r.campo(), r.scope(),
                r.tauAuto(), r.tauRevisar(), r.targetPrecision(), r.samples(), r.attainable(), evaluationId);
        }
    }

    public List<ThresholdRow> thresholds(UUID tenant, String key, String tipologia) {
        StringBuilder sql = new StringBuilder("select tipologia, campo, scope, tau_auto, tau_revisar, attainable, "
            + "samples, target_precision from qa_threshold where tenant_id = ? and model_prompt_key = ?");
        List<Object> args = new ArrayList<>(List.of(tenant, key));
        if (tipologia != null) {
            sql.append(" and tipologia = ?");
            args.add(tipologia);
        }
        sql.append(" order by tipologia, campo");
        return jdbc.query(sql.toString(), (rs, i) -> new ThresholdRow(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getBoolean(6), rs.getInt(7), 0.0,
            0.0, rs.getDouble(8)), args.toArray());
    }

    private static final String EVAL_SELECT = "select id, kind, status, fecha, version_prompt, accuracy, "
        + "precision_micro, recall, f1, ece_raw, ece_calibrated, samples, error from golden_set_evaluation";

    private static EvaluationRow evalRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EvaluationRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
            rs.getObject(4, OffsetDateTime.class), rs.getString(5), dbl(rs, 6), dbl(rs, 7), dbl(rs, 8), dbl(rs, 9),
            dbl(rs, 10), dbl(rs, 11), (Integer) rs.getObject(12), rs.getString(13));
    }

    private static Double dbl(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar", e);
        }
    }

    private Map<String, String> readTruth(String s) {
        try {
            return mapper.readValue(s, TRUTH);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Verdad terreno ilegible", e);
        }
    }

    private EvaluationResult readResult(String s) {
        try {
            return mapper.readValue(s, EvaluationResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Resultado de evaluacion ilegible", e);
        }
    }
}
