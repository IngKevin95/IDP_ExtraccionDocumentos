package com.idp.audit.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Metricas Prometheus del servicio: longitud de cadena, pendientes de anclaje, lag y latencia de expediente. */
@Component
public class AuditMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AuditMetrics.class);

    /** Eventos sin anclar por encima de los cuales se activa la alerta (H11). */
    @Value("${idp.audit.anchor.alert-threshold:5000}")
    private long alertThreshold = 5000;

    private final MeterRegistry registry;
    private final Map<UUID, AtomicLong> unanchoredAlert = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> chainLength = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> unanchored = new ConcurrentHashMap<>();
    private final Timer ingestionLag;
    private final Timer dossierTime;

    public AuditMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingestionLag = Timer.builder("audit.ingestion.lag").register(registry);
        this.dossierTime = Timer.builder("audit.dossier.generation_time").register(registry);
    }

    public void chainLength(UUID tenantId, long value) {
        gauge("audit.chain.length", chainLength, tenantId).set(value);
    }

    public void unanchored(UUID tenantId, long value) {
        gauge("audit.worm.unanchored_count", unanchored, tenantId).set(value);
        boolean exceeded = value > alertThreshold;
        gauge("audit.worm.unanchored_alert", unanchoredAlert, tenantId).set(exceeded ? 1 : 0);
        if (exceeded) {
            count("audit.worm.unanchored_threshold_exceeded");
            LOG.error("CRITICAL eventos sin anclar en WORM superan el umbral: tenant={} pendientes={} umbral={}",
                    tenantId, value, alertThreshold);
        }
    }

    private AtomicLong gauge(String name, Map<UUID, AtomicLong> store, UUID tenantId) {
        return store.computeIfAbsent(tenantId, t -> {
            AtomicLong v = new AtomicLong();
            Gauge.builder(name, v, AtomicLong::get).tag("tenant", t.toString()).register(registry);
            return v;
        });
    }

    public void ingestionLag(Duration lag) {
        ingestionLag.record(lag.isNegative() ? Duration.ZERO : lag);
    }

    public Timer dossierTimer() {
        return dossierTime;
    }

    public void count(String name) {
        Counter.builder(name).register(registry).increment();
    }
}
