package com.idp.audit.application;

import com.idp.audit.infrastructure.AuditRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disparador periodico del anclaje: lote de {@code batch-size} eventos o {@code max-age-hours} (por defecto 1 h). */
@Component
@ConditionalOnProperty(name = "idp.audit.anchor.job-enabled", havingValue = "true", matchIfMissing = true)
public class AnchorJob {

    private static final Logger LOG = LoggerFactory.getLogger(AnchorJob.class);

    private final AuditRepository repo;
    private final WormAnchorService anchors;

    public AnchorJob(AuditRepository repo, WormAnchorService anchors) {
        this.repo = repo;
        this.anchors = anchors;
    }

    @Scheduled(fixedDelayString = "${idp.audit.anchor.interval-ms:60000}")
    public void run() {
        for (UUID tenantId : repo.tenantsWithUnanchored()) {
            try {
                anchors.anchorIfDue(tenantId);
            } catch (RuntimeException e) {
                // Un tenant fallido no frena a los demas; la alerta de pendientes sin anclar avisa si persiste.
                LOG.error("Fallo el anclaje WORM del tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }
}
