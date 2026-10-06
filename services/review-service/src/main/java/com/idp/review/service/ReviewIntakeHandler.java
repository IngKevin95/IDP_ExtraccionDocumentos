package com.idp.review.service;

import com.idp.events.EventEnvelope;
import com.idp.events.EventOriginGuard;
import com.idp.review.config.ReviewProperties;
import com.idp.review.domain.FieldCandidate;
import com.idp.review.domain.FieldStatus;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.FieldCandidateSource;
import com.idp.review.infra.ReviewRepository;
import com.idp.tenant.context.TenantDirectory;
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
    private final TenantDirectory tenants;

    public ReviewIntakeHandler(ReviewRepository repo, FieldCandidateSource fieldSource, CriticalFields critical,
                               ReviewProperties props, MeterRegistry meters, Clock clock, TenantDirectory tenants) {
        this.tenants = tenants;
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

    /**
     * SEC-052: el evento solo prueba que alguien lo publico. La muestra ciega solo se acepta si el tenant esta activo
     * y, en SU silo, el documento esta APROBADO por AUTO_STP y no es ALTAMENTE_CONFIDENCIAL. Si no, se ignora con
     * alerta SECURITY sin payload (un productor comprometido no puede forzar tareas sobre documentos arbitrarios).
     */
    private boolean blindEligible(EventEnvelope e, UUID documentId) {
        String reason = null;
        if (!tenants.activeTenants().contains(e.tenantId().toString())) {
            reason = "tenant_inactive";
        } else {
            var doc = fieldSource.approvedDocument(documentId);
            if (doc.isEmpty()) {
                reason = "document_unverifiable";
            } else if (!"APROBADO".equals(doc.get().status())) {
                reason = "document_not_approved";
            } else if (!"AUTO_STP".equals(doc.get().approvedBy())) {
                reason = "not_auto_approved";
            } else if ("ALTAMENTE_CONFIDENCIAL".equals(doc.get().classification())) {
                reason = "highly_confidential";
            }
        }
        if (reason != null) {
            meters.counter("idp_review_blind_rejected_total", "reason", reason).increment();
            LOG.error(EventOriginGuard.SECURITY, "Muestra ciega ignorada: reason={} documento={} tenant={}", reason,
                    documentId, e.tenantId());
            return false;
        }
        return true;
    }

    /**
     * calidad.muestra_ciega_solicitada: crea la tarea de revision ciega de un oficio ya auto-aprobado. El id de la
     * tarea es el sampleId. Los campos son todos los extraidos (el revisor los transcribe sin ver el valor del modelo ni
     * su score). No toca el estado del documento. Sin campos disponibles no se crea tarea: transcribir nada no mide
     * nada y falsearia la tasa de acuerdo.
     */
    public void handleBlind(EventEnvelope e) {
        UUID taskId = UUID.fromString(e.payload().path("sampleId").asText());
        UUID documentId = UUID.fromString(e.payload().path("documentId").asText());
        if (!blindEligible(e, documentId)) {
            return;
        }
        Map<String, FieldCandidate> unique = new LinkedHashMap<>();
        for (FieldCandidate c : fieldSource.approvedFields(documentId)) {
            unique.putIfAbsent(c.fieldName(), c);
        }
        if (unique.isEmpty()) {
            LOG.warn("Muestra ciega {} sin campos extraidos disponibles: no se crea tarea", taskId);
            meters.counter("idp_review_blind_unavailable_total").increment();
            return;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ReviewTask task = new ReviewTask(taskId, e.tenantId().toString(), documentId, e.correlationId(),
                TaskStatus.PENDING, null, null, null, null, false, now.plus(props.sla()), 0, null, null, now, now,
                true);
        if (!repo.insertTaskIfAbsent(task)) {
            LOG.debug("Tarea ciega {} ya existe, evento ignorado", taskId);
            return;
        }
        repo.setOrigin(taskId, e.payload().path("typology").asText(null), true);
        for (FieldCandidate c : unique.values()) {
            repo.insertField(new ReviewField(UUID.randomUUID(), taskId, c.fieldName(), c.page(), c.boundingBox(),
                    c.originalValue(), c.confidence(), critical.isCritical(c.fieldName()), FieldStatus.PENDING,
                    now));
        }
        meters.counter("idp_review_blind_tasks_created_total").increment();
        LOG.info("Tarea ciega {} creada con {} campos", taskId, unique.size());
    }
}
