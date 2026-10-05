package com.idp.review.infra;

import com.idp.review.domain.Correction;
import com.idp.review.domain.FieldStatus;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acceso JDBC al silo del tenant actual (el DataSource enrutado lo resuelve por contexto). Todas las transiciones de
 * estado y asignaciones son UPDATE condicionales: el numero de filas afectadas decide el ganador ante concurrencia.
 */
@Repository
public class ReviewRepository {

    public enum Scope { ALL, MINE, UNASSIGNED }

    /** Fila de la cola por campo: un campo pendiente con los datos de su tarea. */
    public record QueueItem(UUID fieldId, UUID taskId, UUID documentId, String fieldName, boolean critical,
                            Integer page, BigDecimal confidence, TaskStatus taskStatus, String assigneeId,
                            OffsetDateTime slaDueAt, int escalationLevel) {
    }

    /** Contadores para el tablero de metricas. */
    public record Stats(Map<TaskStatus, Long> byStatus, long unassigned, long overdue, long escalated,
                        Double avgCycleSeconds) {
    }

    private static final String OPEN = "('PENDING','PENDING_SECOND_APPROVAL')";
    private static final String TASK_COLS = "id, tenant_id, document_id, correlation_id, status, assignee_id, "
            + "assigned_at, first_reviewer_id, second_reviewer_id, critical_correction, sla_due_at, "
            + "escalation_level, escalated_at, completed_at, created_at, updated_at";
    private static final String FIELD_COLS = "id, task_id, field_name, page, bounding_box, original_value, "
            + "confidence, critical, status, created_at";
    private static final String CORRECTION_COLS = "id, task_id, field_name, original_value, corrected_value, "
            + "is_critical, created_at, created_by";

    private static final RowMapper<ReviewTask> TASK = (rs, i) -> new ReviewTask(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class).toString(),
            rs.getObject("document_id", UUID.class),
            rs.getObject("correlation_id", UUID.class),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getString("assignee_id"),
            rs.getObject("assigned_at", OffsetDateTime.class),
            rs.getString("first_reviewer_id"),
            rs.getString("second_reviewer_id"),
            rs.getBoolean("critical_correction"),
            rs.getObject("sla_due_at", OffsetDateTime.class),
            rs.getInt("escalation_level"),
            rs.getObject("escalated_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<ReviewField> FIELD = (rs, i) -> {
        int page = rs.getInt("page");
        Integer pageOrNull = rs.wasNull() ? null : page;
        return new ReviewField(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getString("field_name"), pageOrNull, rs.getString("bounding_box"),
                rs.getString("original_value"), rs.getBigDecimal("confidence"), rs.getBoolean("critical"),
                FieldStatus.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class));
    };

    private static final RowMapper<Correction> CORRECTION = (rs, i) -> new Correction(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("field_name"),
            rs.getString("original_value"), rs.getString("corrected_value"), rs.getBoolean("is_critical"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));

    private static final RowMapper<QueueItem> QUEUE_ITEM = (rs, i) -> {
        int page = rs.getInt("page");
        Integer pageOrNull = rs.wasNull() ? null : page;
        return new QueueItem(rs.getObject("field_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("field_name"), rs.getBoolean("critical"),
                pageOrNull, rs.getBigDecimal("confidence"), TaskStatus.valueOf(rs.getString("status")),
                rs.getString("assignee_id"), rs.getObject("sla_due_at", OffsetDateTime.class),
                rs.getInt("escalation_level"));
    };

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- altas -----------------------------------------------------------------------------------------------

    /** Inserta la tarea si no existe ese id (idempotencia por taskId). @return true si se inserto. */
    public boolean insertTaskIfAbsent(ReviewTask t) {
        return jdbc.update("insert into review_task (" + TASK_COLS + ") select cast(? as uuid), cast(? as uuid), cast(? as uuid), "
                + "cast(? as uuid), cast(? as varchar(32)), cast(? as varchar(255)), cast(? as timestamp with time zone), "
                + "cast(? as varchar(255)), cast(? as varchar(255)), cast(? as boolean), "
                + "cast(? as timestamp with time zone), cast(? as integer), cast(? as timestamp with time zone), "
                + "cast(? as timestamp with time zone), cast(? as timestamp with time zone), "
                + "cast(? as timestamp with time zone) "
                + "where not exists (select 1 from review_task x where x.id = cast(? as uuid))",
                t.id(), UUID.fromString(t.tenantId()), t.documentId(), t.correlationId(), t.status().name(),
                t.assigneeId(), t.assignedAt(), t.firstReviewerId(), t.secondReviewerId(), t.criticalCorrection(),
                t.slaDueAt(), t.escalationLevel(), t.escalatedAt(), t.completedAt(), t.createdAt(), t.updatedAt(),
                t.id()) == 1;
    }

    public void insertField(ReviewField f) {
        jdbc.update("insert into review_field (" + FIELD_COLS + ") values (?,?,?,?,?,?,?,?,?,?)", f.id(),
                f.taskId(), f.fieldName(), f.page(), f.boundingBox(), f.originalValue(), f.confidence(),
                f.critical(), f.status().name(), f.createdAt());
    }

    /** Alta o sobrescritura de la correccion de un campo de la tarea. */
    public void upsertCorrection(Correction c) {
        int updated = jdbc.update("update correction set original_value = ?, corrected_value = ?, is_critical = ?, "
                + "created_at = ?, created_by = ? where task_id = ? and field_name = ?", c.originalValue(),
                c.correctedValue(), c.critical(), c.createdAt(), c.createdBy(), c.taskId(), c.fieldName());
        if (updated == 0) {
            jdbc.update("insert into correction (" + CORRECTION_COLS + ") values (?,?,?,?,?,?,?,?)", c.id(),
                    c.taskId(), c.fieldName(), c.originalValue(), c.correctedValue(), c.critical(), c.createdAt(),
                    c.createdBy());
        }
    }

    // ---- lecturas --------------------------------------------------------------------------------------------

    public Optional<ReviewTask> findTask(String tenantId, UUID id) {
        return jdbc.query("select " + TASK_COLS + " from review_task where tenant_id = ? and id = ?", TASK,
                UUID.fromString(tenantId), id).stream().findFirst();
    }

    public List<ReviewTask> listTasks(String tenantId, TaskStatus status, String assignee, boolean escalatedOnly,
                                      int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = taskFilter(tenantId, status, assignee, escalatedOnly, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select " + TASK_COLS + " from review_task where " + where
                + " order by escalation_level desc, sla_due_at asc, id limit ? offset ?", TASK, args.toArray());
    }

    public long countTasks(String tenantId, TaskStatus status, String assignee, boolean escalatedOnly) {
        List<Object> args = new ArrayList<>();
        String where = taskFilter(tenantId, status, assignee, escalatedOnly, args);
        return count("select count(*) from review_task where " + where, args.toArray());
    }

    private static String taskFilter(String tenantId, TaskStatus status, String assignee, boolean escalatedOnly,
                                     List<Object> args) {
        StringBuilder w = new StringBuilder("tenant_id = ?");
        args.add(UUID.fromString(tenantId));
        if (status != null) {
            w.append(" and status = ?");
            args.add(status.name());
        }
        if (assignee != null) {
            w.append(" and assignee_id = ?");
            args.add(assignee);
        }
        if (escalatedOnly) {
            w.append(" and escalation_level > 0");
        }
        return w.toString();
    }

    public List<ReviewField> fields(UUID taskId) {
        return jdbc.query("select " + FIELD_COLS + " from review_field where task_id = ? order by created_at, "
                + "field_name", FIELD, taskId);
    }

    public Optional<ReviewField> field(UUID taskId, UUID fieldId) {
        return jdbc.query("select " + FIELD_COLS + " from review_field where task_id = ? and id = ?", FIELD,
                taskId, fieldId).stream().findFirst();
    }

    public List<Correction> corrections(UUID taskId) {
        return jdbc.query("select " + CORRECTION_COLS + " from correction where task_id = ? order by created_at, "
                + "field_name", CORRECTION, taskId);
    }

    public boolean hasCriticalCorrection(UUID taskId) {
        return count("select count(*) from correction where task_id = ? and is_critical = true", taskId) > 0;
    }

    /** Cola por campo: campos pendientes de tareas abiertas, escaladas y mas vencidas primero. */
    public List<QueueItem> queue(String tenantId, Scope scope, String userId, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = queueFilter(tenantId, scope, userId, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select f.id as field_id, t.id as task_id, t.document_id, f.field_name, f.critical, "
                + "f.page, f.confidence, t.status, t.assignee_id, t.sla_due_at, t.escalation_level "
                + "from review_field f join review_task t on t.id = f.task_id where " + where
                + " order by t.escalation_level desc, t.sla_due_at asc, f.created_at, f.field_name limit ? offset ?",
                QUEUE_ITEM, args.toArray());
    }

    public long countQueue(String tenantId, Scope scope, String userId) {
        List<Object> args = new ArrayList<>();
        String where = queueFilter(tenantId, scope, userId, args);
        return count("select count(*) from review_field f join review_task t on t.id = f.task_id where " + where,
                args.toArray());
    }

    private static String queueFilter(String tenantId, Scope scope, String userId, List<Object> args) {
        StringBuilder w = new StringBuilder("t.tenant_id = ? and f.status = 'PENDING' and t.status in " + OPEN);
        args.add(UUID.fromString(tenantId));
        if (scope == Scope.MINE) {
            w.append(" and t.assignee_id = ?");
            args.add(userId);
        } else if (scope == Scope.UNASSIGNED) {
            w.append(" and t.assignee_id is null");
        }
        return w.toString();
    }

    // ---- asignacion ------------------------------------------------------------------------------------------

    /** Toma la tarea: gana quien la encuentra libre o ya suya; el primer revisor no toma la segunda aprobacion. */
    public boolean claim(String tenantId, UUID id, String userId, OffsetDateTime now) {
        return jdbc.update("update review_task set assignee_id = ?, assigned_at = ?, updated_at = ? "
                + "where tenant_id = ? and id = ? and status in " + OPEN + " and (assignee_id is null or "
                + "assignee_id = ?) and (first_reviewer_id is null or first_reviewer_id <> ?)",
                userId, now, now, UUID.fromString(tenantId), id, userId, userId) == 1;
    }

    public boolean release(String tenantId, UUID id, String userId, OffsetDateTime now) {
        return jdbc.update("update review_task set assignee_id = null, assigned_at = null, updated_at = ? "
                + "where tenant_id = ? and id = ? and status in " + OPEN + " and assignee_id = ?", now,
                UUID.fromString(tenantId), id, userId) == 1;
    }

    /** Reasignacion por un supervisor; en segunda aprobacion el destino no puede ser el primer revisor. */
    public boolean reassign(String tenantId, UUID id, String assignee, OffsetDateTime now) {
        return jdbc.update("update review_task set assignee_id = ?, assigned_at = ?, updated_at = ? "
                + "where tenant_id = ? and id = ? and status in " + OPEN
                + " and (first_reviewer_id is null or first_reviewer_id <> ?)", assignee, now, now,
                UUID.fromString(tenantId), id, assignee) == 1;
    }

    // ---- transiciones ----------------------------------------------------------------------------------------

    /** Primera aprobacion: PENDING a APPROVED o PENDING_SECOND_APPROVAL. */
    public boolean firstApprove(String tenantId, UUID id, String reviewer, boolean critical, OffsetDateTime now,
                                long cycleSeconds) {
        if (critical) {
            return jdbc.update("update review_task set status = 'PENDING_SECOND_APPROVAL', first_reviewer_id = ?, "
                    + "critical_correction = true, assignee_id = null, assigned_at = null, updated_at = ? "
                    + "where tenant_id = ? and id = ? and status = 'PENDING' and (assignee_id is null or "
                    + "assignee_id = ?)", reviewer, now, UUID.fromString(tenantId), id, reviewer) == 1;
        }
        return jdbc.update("update review_task set status = 'APPROVED', first_reviewer_id = ?, "
                + "critical_correction = false, assignee_id = ?, completed_at = ?, cycle_seconds = ?, "
                + "updated_at = ? where tenant_id = ? and id = ? and status = 'PENDING' and "
                + "(assignee_id is null or assignee_id = ?)", reviewer, reviewer, now, cycleSeconds, now,
                UUID.fromString(tenantId), id, reviewer) == 1;
    }

    /** Segunda aprobacion: el aprobador debe ser distinto del primer revisor (SEC-009). */
    public boolean secondApprove(String tenantId, UUID id, String reviewer, OffsetDateTime now, long cycleSeconds) {
        return jdbc.update("update review_task set status = 'APPROVED', second_reviewer_id = ?, assignee_id = ?, "
                + "completed_at = ?, cycle_seconds = ?, updated_at = ? where tenant_id = ? and id = ? and "
                + "status = 'PENDING_SECOND_APPROVAL' and first_reviewer_id <> ? and (assignee_id is null or "
                + "assignee_id = ?)", reviewer, reviewer, now, cycleSeconds, now, UUID.fromString(tenantId), id,
                reviewer, reviewer) == 1;
    }

    public boolean reject(String tenantId, UUID id, String reviewer, OffsetDateTime now, long cycleSeconds) {
        return jdbc.update("update review_task set status = 'REJECTED', first_reviewer_id = coalesce("
                + "first_reviewer_id, ?), assignee_id = ?, completed_at = ?, cycle_seconds = ?, updated_at = ? "
                + "where tenant_id = ? and id = ? and status in " + OPEN + " and (assignee_id is null or "
                + "assignee_id = ?)", reviewer, reviewer, now, cycleSeconds, now, UUID.fromString(tenantId), id,
                reviewer) == 1;
    }

    public void markFieldCorrected(UUID taskId, String fieldName) {
        jdbc.update("update review_field set status = 'CORRECTED' where task_id = ? and field_name = ?", taskId,
                fieldName);
    }

    /** El revisor que aprueba da por confirmados los campos que no corrigio. */
    public void confirmPendingFields(UUID taskId) {
        jdbc.update("update review_field set status = 'CONFIRMED' where task_id = ? and status = 'PENDING'",
                taskId);
    }

    // ---- SLA y metricas --------------------------------------------------------------------------------------

    public List<UUID> overdueTaskIds(OffsetDateTime now, int maxLevel, int limit) {
        return jdbc.queryForList("select id from review_task where status in " + OPEN + " and sla_due_at < ? and "
                + "escalation_level < ? order by sla_due_at, id limit ?", UUID.class, now, maxLevel, limit);
    }

    /** Escala una vez por vencimiento: la condicion sobre sla_due_at evita doble escalamiento entre instancias. */
    public boolean escalate(UUID id, OffsetDateTime now, OffsetDateTime newDue, int maxLevel) {
        return jdbc.update("update review_task set escalation_level = escalation_level + 1, escalated_at = ?, "
                + "sla_due_at = ?, updated_at = ? where id = ? and status in " + OPEN
                + " and sla_due_at < ? and escalation_level < ?", now, newDue, now, id, now, maxLevel) == 1;
    }

    public Stats stats(String tenantId, OffsetDateTime now) {
        UUID tenant = UUID.fromString(tenantId);
        Map<TaskStatus, Long> byStatus = new LinkedHashMap<>();
        for (TaskStatus s : TaskStatus.values()) {
            byStatus.put(s, 0L);
        }
        jdbc.query("select status, count(*) as n from review_task where tenant_id = ? group by status",
                rs -> {
                    byStatus.put(TaskStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                }, tenant);
        long unassigned = count("select count(*) from review_task where tenant_id = ? and status in " + OPEN
                + " and assignee_id is null", tenant);
        long overdue = count("select count(*) from review_task where tenant_id = ? and status in " + OPEN
                + " and sla_due_at < ?", tenant, now);
        long escalated = count("select count(*) from review_task where tenant_id = ? and status in " + OPEN
                + " and escalation_level > 0", tenant);
        Double avg = jdbc.queryForObject("select avg(cycle_seconds) from review_task where tenant_id = ? and "
                + "cycle_seconds is not null", Double.class, tenant);
        return new Stats(byStatus, unassigned, overdue, escalated, avg);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
