package com.idp.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AuditMetricsTest {

    @Test
    void h11_alertaSiLosEventosSinAnclarSuperanElUmbral() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditMetrics metrics = new AuditMetrics(registry);
        ReflectionTestUtils.setField(metrics, "alertThreshold", 100L);
        UUID tenant = UUID.randomUUID();

        metrics.unanchored(tenant, 100);
        assertEquals(0.0, registry.get("audit.worm.unanchored_alert").gauge().value());

        metrics.unanchored(tenant, 101);
        assertEquals(1.0, registry.get("audit.worm.unanchored_alert").gauge().value());
        assertEquals(1.0, registry.get("audit.worm.unanchored_threshold_exceeded").counter().count());

        metrics.unanchored(tenant, 3);
        assertEquals(0.0, registry.get("audit.worm.unanchored_alert").gauge().value());
    }
}
