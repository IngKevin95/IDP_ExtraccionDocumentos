package com.idp.review.service;

import com.idp.review.config.ReviewProperties;
import com.idp.review.domain.ReviewTask;
import com.idp.review.infra.ReviewEvents;
import com.idp.review.infra.ReviewRepository;
import com.idp.tenant.context.TenantContextHolder;
import com.idp.tenant.context.TenantDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SLA y escalamiento: una tarea abierta cuyo sla_due_at vencio sube un nivel de escalamiento y rearma su vencimiento
 * (escalation-interval) hasta max-escalation-level. Las tareas escaladas encabezan la cola. El UPDATE condicional hace
 * el escalamiento seguro entre varias instancias del servicio.
 */
@Service
public class SlaEscalationService {

    private static final Logger LOG = LoggerFactory.getLogger(SlaEscalationService.class);
    private static final int BATCH = 200;

    private final ReviewRepository repo;
    private final ReviewEvents events;
    private final TenantDirectory tenants;
    private final ReviewProperties props;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public SlaEscalationService(ReviewRepository repo, ReviewEvents events, TenantDirectory tenants, ReviewProperties props,
                                TransactionTemplate tx, MeterRegistry meters, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.tenants = tenants;
        this.props = props;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /** Escala los tenants activos; el fallo de uno no bloquea a los demas. Devuelve tareas escaladas. */
    public int escalateAll() {
        int total = 0;
        for (String tenant : tenants.activeTenants()) {
            try {
                total += escalateTenant(tenant);
            } catch (RuntimeException e) {
                LOG.warn("Escalamiento del tenant {} fallo: {}", tenant, e.getClass().getSimpleName());
            }
        }
        return total;
    }

    /** Escala y publica revision.escalada por outbox en la misma transaccion. */
    private boolean escalateOne(String tenantId, UUID id, OffsetDateTime now, int max) {
        ReviewTask before = repo.findTask(tenantId, id).orElse(null);
        if (before == null || !repo.escalate(id, now, now.plus(props.escalationInterval()), max)) {
            return false;
        }
        repo.findTask(tenantId, id).ifPresent(after -> events.escalada(after, before.slaDueAt()));
        return true;
    }

    public int escalateTenant(String tenantId) {
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            int max = props.maxEscalationLevel();
            List<UUID> due = tx.execute(s -> repo.overdueTaskIds(now, max, BATCH));
            int escalated = 0;
            for (UUID id : due == null ? List.<UUID>of() : due) {
                Boolean done = tx.execute(s -> escalateOne(tenantId, id, now, max));
                if (Boolean.TRUE.equals(done)) {
                    escalated++;
                    meters.counter("idp_review_escalated_total").increment();
                    LOG.warn("ALERTA SLA vencido: tarea {} escalada (tenant {})", id, tenantId);
                }
            }
            return escalated;
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }
}
