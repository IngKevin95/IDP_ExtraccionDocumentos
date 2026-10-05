package com.idp.quality.config;

import com.idp.quality.metrics.DriftService;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Job periodico de deriva: evalua el dia cerrado y el dia en curso y persiste las alertas (AC-08). */
@Component
@ConditionalOnProperty(name = "quality.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class QualityScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(QualityScheduler.class);

    private final DriftService drift;
    private final Clock clock;

    public QualityScheduler(DriftService drift, Clock clock) {
        this.drift = drift;
        this.clock = clock;
    }

    @Scheduled(cron = "${quality.scheduler.cron:0 15 1 * * *}", zone = "UTC")
    public void evaluateDrift() {
        LocalDate today = LocalDate.now(clock);
        int alerts = drift.evaluateAll(today.minusDays(1)) + drift.evaluateAll(today);
        LOG.info("Evaluacion de deriva completada, alertas={}", alerts);
    }
}
