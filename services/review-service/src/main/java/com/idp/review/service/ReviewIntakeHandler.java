package com.idp.review.service;

import com.idp.events.EventEnvelope;
import com.idp.review.config.ReviewProperties;
import com.idp.review.domain.FieldCandidate;
import com.idp.review.domain.FieldStatus;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.FieldCandidateSource;
import com.idp.review.infra.ReviewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Crea la tarea de revision (estado PENDING) al consumir extraccion.requiere_revision. Se invoca dentro de la
 * transaccion del consumidor idempotente, con el tenant del evento ya fijado. El id de la tarea es el taskId del
 * evento: un reintento con otro eventId pero el mismo taskId tampoco duplica la tarea (AC-02).
 */
@Service
public class ReviewIntakeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewIntakeHandler.class);

    private final ReviewRepository repo;
    private final FieldCandidateSource fieldSource;
    private final CriticalFields critical;
    private final ReviewProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReviewIntakeHandler(ReviewRepository repo, FieldCandidateSource fieldSource, CriticalFields critical,
                               ReviewProperties props, MeterRegistry meters, Clock clock) {
        this.repo = repo;
        this.fieldSource = fieldSource;
        this.critical = critical;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    public void handle(EventEnvelope e) {
        UUID taskId = UUID.fromString(e.payload().path("taskId").asText());
        UUID documentId = UUID.fromString(e.payload().path("documentId").asText());
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ReviewTask task = new ReviewTask(taskId, e.tenantId().toString(), documentId, e.correlationId(),
                TaskStatus.PENDING, null, null, null, null, false, now.plus(props.sla()), 0, null, null, now, now);
        if (!repo.insertTaskIfAbsent(task)) {
            LOG.debug("Tarea {} ya existe, evento ignorado", taskId);
            return;
        }
        String typology = e.payload().path("typology").asText(null);
        boolean blind = e.payload().path("blindSample").asBoolean(false);
        if (typology != null || blind) {
            repo.setOrigin(taskId, typology, blind);
        }
        Map<String, FieldCandidate> unique = new LinkedHashMap<>();
        for (FieldCandidate c : fieldSource.candidates(documentId, taskId)) {
            unique.putIfAbsent(c.fieldName(), c);
        }
        for (FieldCandidate c : unique.values()) {
            repo.insertField(new ReviewField(UUID.randomUUID(), taskId, c.fieldName(), c.page(), c.boundingBox(),
                    c.originalValue(), c.confidence(), critical.isCritical(c.fieldName()), FieldStatus.PENDING,
                    now));
        }
        meters.counter("idp_review_tasks_created_total").increment();
        LOG.info("Tarea de revision {} creada con {} campos", taskId, unique.size());
    }
}
