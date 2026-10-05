package com.idp.review.service;

import com.idp.review.domain.Correction;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.ReviewEvents;
import com.idp.review.infra.ReviewRepository;
import com.idp.review.service.Exceptions.ConflictException;
import com.idp.review.service.Exceptions.FourEyesViolationException;
import com.idp.review.service.Exceptions.InvalidRequestException;
import com.idp.review.service.Exceptions.InvalidStateException;
import com.idp.review.service.Exceptions.TaskNotFoundException;
import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flujo de la tarea de revision: asignacion sin doble asignacion, correcciones por campo, aprobacion simple, cuatro
 * ojos en campos criticos (SEC-009) y rechazo. Cada operacion es una transaccion que cambia el estado y publica el
 * evento por outbox en la misma transaccion. El tenant y el usuario salen del {@link Caller} (JWT revalidado).
 */
@Service
public class ReviewTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewTaskService.class);
    private static final int MAX_CORRECTIONS = 200;
    private static final int MAX_FIELD_NAME = 200;
    private static final int MAX_VALUE = 10_000;

    /** Correccion solicitada por el revisor (originalValue opcional). */
    public record CorrectionInput(String fieldName, String originalValue, String correctedValue) {
    }

    private final ReviewRepository repo;
    private final ReviewEvents events;
    private final CriticalFields critical;
    private final RoleAssignmentVerifier roles;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReviewTaskService(ReviewRepository repo, ReviewEvents events, CriticalFields critical,
                             RoleAssignmentVerifier roles, TransactionTemplate tx, MeterRegistry meters,
                             Clock clock) {
        this.repo = repo;
        this.events = events;
        this.critical = critical;
        this.roles = roles;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    // ---- asignacion ------------------------------------------------------------------------------------------

    /** Toma la tarea para el revisor. Idempotente para el mismo revisor; 409 si la tiene otro. */
    public ReviewTask claim(Caller caller, UUID taskId) {
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            requireOpen(task);
            if (!repo.claim(caller.tenantId(), taskId, caller.userId(), now())) {
                throw failure(load(caller, taskId), caller, Expected.OPEN);
            }
            meters.counter("idp_review_claimed_total").increment();
            return load(caller, taskId);
        });
    }

    /** El asignado devuelve la tarea a la cola. */
    public ReviewTask release(Caller caller, UUID taskId) {
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            requireOpen(task);
            if (!repo.release(caller.tenantId(), taskId, caller.userId(), now())) {
                throw new ConflictException("REVIEW_NOT_ASSIGNEE", "La tarea no esta asignada a este revisor");
            }
            return load(caller, taskId);
        });
    }

    /** Un supervisor reasigna la tarea a otro revisor con rol vigente (en segunda aprobacion, distinto del primero). */
    public ReviewTask reassign(Caller caller, UUID taskId, String assigneeId) {
        if (assigneeId == null || assigneeId.isBlank() || assigneeId.length() > 255) {
            throw new InvalidRequestException("assigneeId invalido");
        }
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            requireOpen(task);
            if (!roles.hasRole(caller.tenantId(), assigneeId, Roles.REVISOR)) {
                throw new InvalidRequestException("El destinatario no tiene el rol REVISOR vigente");
            }
            if (task.status() == TaskStatus.PENDING_SECOND_APPROVAL && assigneeId.equals(task.firstReviewerId())) {
                meters.counter("idp_review_four_eyes_denied_total").increment();
                throw new FourEyesViolationException("El primer revisor no puede tomar la segunda aprobacion");
            }
            if (!repo.reassign(caller.tenantId(), taskId, assigneeId, now())) {
                throw failure(load(caller, taskId), caller, Expected.OPEN);
            }
            meters.counter("idp_review_reassigned_total").increment();
            LOG.info("Tarea {} reasignada por {}", taskId, caller.userId());
            return load(caller, taskId);
        });
    }

    // ---- correcciones ----------------------------------------------------------------------------------------

    /**
     * Agrega o sobrescribe correcciones por campo mientras la tarea esta PENDING. Quien corrige toma la tarea de forma
     * atomica. La criticidad la decide el servidor, nunca el cliente.
     */
    public List<Correction> addCorrections(Caller caller, UUID taskId, List<CorrectionInput> inputs) {
        validate(inputs);
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            if (task.status() != TaskStatus.PENDING) {
                throw new InvalidStateException("Solo se corrige una tarea en estado PENDING");
            }
            if (!repo.claim(caller.tenantId(), taskId, caller.userId(), now())) {
                throw failure(load(caller, taskId), caller, Expected.PENDING_ONLY);
            }
            Map<String, ReviewField> known = new java.util.HashMap<>();
            repo.fields(taskId).forEach(f -> known.put(f.fieldName(), f));
            OffsetDateTime now = now();
            for (CorrectionInput in : inputs) {
                String name = in.fieldName().trim();
                ReviewField field = known.get(name);
                String original = in.originalValue() != null ? in.originalValue()
                        : field == null ? null : field.originalValue();
                repo.upsertCorrection(new Correction(UUID.randomUUID(), taskId, name, original,
                        in.correctedValue(), critical.isCritical(name), now, caller.userId()));
                repo.markFieldCorrected(taskId, name);
            }
            LOG.info("Tarea {}: {} correcciones registradas", taskId, inputs.size());
            return repo.corrections(taskId);
        });
    }

    // ---- aprobacion y rechazo --------------------------------------------------------------------------------

    /**
     * Primera aprobacion. Sin correcciones criticas la tarea queda APPROVED y se emite revision.completada; con ellas
     * pasa a PENDING_SECOND_APPROVAL, se libera la asignacion y no se emite evento (RF-302).
     */
    public ReviewTask approve(Caller caller, UUID taskId) {
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            if (task.status() != TaskStatus.PENDING) {
                throw new InvalidStateException("Solo se aprueba una tarea en estado PENDING");
            }
            boolean needsSecond = repo.hasCriticalCorrection(taskId);
            OffsetDateTime now = now();
            if (!repo.firstApprove(caller.tenantId(), taskId, caller.userId(), needsSecond, now,
                    cycleSeconds(task, now))) {
                throw failure(load(caller, taskId), caller, Expected.PENDING_ONLY);
            }
            repo.confirmPendingFields(taskId);
            ReviewTask updated = load(caller, taskId);
            if (needsSecond) {
                meters.counter("idp_review_second_approval_requested_total").increment();
            } else {
                events.completada(updated, ReviewEvents.APROBADO, caller.userId(), null, false);
                completed(updated, ReviewEvents.APROBADO);
            }
            return updated;
        });
    }

    /** Segunda aprobacion (cuatro ojos): otro revisor, distinto del primero, ambos con rol vigente. */
    public ReviewTask approveSecondary(Caller caller, UUID taskId) {
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            if (task.status() != TaskStatus.PENDING_SECOND_APPROVAL) {
                throw new InvalidStateException("La tarea no esta pendiente de segunda aprobacion");
            }
            String first = task.firstReviewerId();
            if (first == null || first.equals(caller.userId())) {
                meters.counter("idp_review_four_eyes_denied_total").increment();
                throw new FourEyesViolationException("El segundo aprobador debe ser distinto del primer revisor");
            }
            if (!roles.hasRole(caller.tenantId(), first, Roles.REVISOR)) {
                throw new ConflictException("REVIEW_FIRST_REVIEWER_INVALID",
                        "El primer revisor ya no tiene el rol REVISOR vigente");
            }
            OffsetDateTime now = now();
            if (!repo.secondApprove(caller.tenantId(), taskId, caller.userId(), now, cycleSeconds(task, now))) {
                throw failure(load(caller, taskId), caller, Expected.SECOND_ONLY);
            }
            ReviewTask updated = load(caller, taskId);
            events.completada(updated, ReviewEvents.APROBADO, first, caller.userId(), true);
            completed(updated, ReviewEvents.APROBADO);
            return updated;
        });
    }

    /**
     * Rechazo por cualquier revisor, en primera o segunda instancia. El rechazo descarta las correcciones, por lo que
     * el evento va con criticalCorrection=false y sin segundo aprobador.
     */
    public ReviewTask reject(Caller caller, UUID taskId) {
        return inTx(() -> {
            ReviewTask task = load(caller, taskId);
            requireOpen(task);
            OffsetDateTime now = now();
            if (!repo.reject(caller.tenantId(), taskId, caller.userId(), now, cycleSeconds(task, now))) {
                throw failure(load(caller, taskId), caller, Expected.OPEN);
            }
            ReviewTask updated = load(caller, taskId);
            events.completada(updated, ReviewEvents.RECHAZADO, caller.userId(), null, false);
            completed(updated, ReviewEvents.RECHAZADO);
            return updated;
        });
    }

    // ---- utilidades ------------------------------------------------------------------------------------------

    private void completed(ReviewTask task, String action) {
        meters.counter("idp_review_completed_total", "action", action).increment();
        if (task.completedAt() != null) {
            meters.timer("idp_review_cycle").record(Duration.between(task.createdAt(), task.completedAt()));
        }
    }

    private ReviewTask load(Caller caller, UUID taskId) {
        return repo.findTask(caller.tenantId(), taskId).orElseThrow(TaskNotFoundException::new);
    }

    private static void requireOpen(ReviewTask task) {
        if (!task.status().open()) {
            throw new InvalidStateException("La tarea ya esta cerrada");
        }
    }

    /** Explica por que fallo un UPDATE condicional leyendo el estado actual. */
    private RuntimeException failure(ReviewTask current, Caller caller, Expected expected) {
        if (!expected.accepts(current.status())) {
            return new InvalidStateException("La tarea no esta en un estado valido para esta operacion");
        }
        if (current.assigneeId() != null && !current.assigneeId().equals(caller.userId())) {
            return new ConflictException("REVIEW_TASK_ASSIGNED", "La tarea esta asignada a otro revisor");
        }
        if (current.status() == TaskStatus.PENDING_SECOND_APPROVAL
                && caller.userId().equals(current.firstReviewerId())) {
            meters.counter("idp_review_four_eyes_denied_total").increment();
            return new FourEyesViolationException("El primer revisor no puede completar la segunda aprobacion");
        }
        return new ConflictException("REVIEW_CONFLICT", "La tarea cambio de estado, reintente");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static long cycleSeconds(ReviewTask task, OffsetDateTime now) {
        return Math.max(0, Duration.between(task.createdAt(), now).getSeconds());
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private static void validate(List<CorrectionInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_CORRECTIONS) {
            throw new InvalidRequestException("Se esperan entre 1 y " + MAX_CORRECTIONS + " correcciones");
        }
        for (CorrectionInput in : inputs) {
            if (in == null || in.fieldName() == null || in.fieldName().isBlank()
                    || in.fieldName().length() > MAX_FIELD_NAME) {
                throw new InvalidRequestException("fieldName invalido");
            }
            if (in.correctedValue() == null || in.correctedValue().length() > MAX_VALUE
                    || (in.originalValue() != null && in.originalValue().length() > MAX_VALUE)) {
                throw new InvalidRequestException("Valor de correccion invalido");
            }
        }
    }

    /** Conjuntos de estados aceptados por una operacion, para clasificar el fallo de un UPDATE condicional. */
    private enum Expected {
        OPEN, PENDING_ONLY, SECOND_ONLY;

        boolean accepts(TaskStatus s) {
            return switch (this) {
                case OPEN -> s.open();
                case PENDING_ONLY -> s == TaskStatus.PENDING;
                case SECOND_ONLY -> s == TaskStatus.PENDING_SECOND_APPROVAL;
            };
        }
    }
}
