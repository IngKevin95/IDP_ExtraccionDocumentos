package com.idp.review.service;

import com.idp.review.domain.ReviewTask;
import com.idp.review.infra.FieldCandidateSource;
import com.idp.review.infra.ReviewRepository;
import com.idp.review.service.Exceptions.FourEyesViolationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Independencia del revisor ciego: quien ya intervino en el documento (cargo el archivo o fue primer o segundo revisor
 * o asignado en otra tarea) no puede hacer su revision ciega, porque sesgaria la medicion del error silente. Solo se
 * puede comprobar con los datos que existen en el silo; no exige un segundo revisor (es medicion, no correccion).
 */
@Component
public class BlindReviewPolicy {

    private final ReviewRepository repo;
    private final FieldCandidateSource source;
    private final MeterRegistry meters;

    public BlindReviewPolicy(ReviewRepository repo, FieldCandidateSource source, MeterRegistry meters) {
        this.repo = repo;
        this.source = source;
        this.meters = meters;
    }

    /** No hace nada en tareas normales; en una ciega lanza 403 si {@code userId} ya intervino en el documento. */
    public void requireIndependent(ReviewTask task, String userId) {
        if (!task.blindSample()) {
            return;
        }
        boolean intervened = repo.hasIntervened(task.documentId(), task.id(), userId)
                || source.uploader(task.documentId()).filter(userId::equals).isPresent();
        if (intervened) {
            meters.counter("idp_review_blind_independence_denied_total").increment();
            throw new FourEyesViolationException("El revisor ciego debe ser distinto de quien intervino en el documento");
        }
    }
}
