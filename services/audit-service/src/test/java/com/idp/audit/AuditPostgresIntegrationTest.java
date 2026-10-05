package com.idp.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.audit.application.AuditVerificationService;
import com.idp.audit.support.AuditTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway, JSONB, bloqueo por tenant, aislamiento (AC-05) e inmutabilidad (regla 3) contra PostgreSQL real.
 * Se omite sin Docker; corre en CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuditPostgresIntegrationTest extends AuditTestSupport {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AuditVerificationService verification;

    @Test
    void ac05_tenantsConcurrentesSobrePostgresMantienenCadenasIntegras() throws Exception {
        UUID a = newTenant();
        UUID b = newTenant();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                fs.add(pool.submit(() -> consume(aprobada(a, UUID.randomUUID()))));
                fs.add(pool.submit(() -> consume(aprobada(b, UUID.randomUUID()))));
            }
            for (Future<?> f : fs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID t : List.of(a, b)) {
            assertEquals(25, countEntries(t));
            var report = verification.verify(t, null, null);
            assertTrue(report.isChainIntact(), report.errorsFound().toString());
            assertEquals(25, report.totalRecordsVerified());
        }
    }

    @Test
    void audit_entriesEsInmutableSoloWormAnchoredPuedeMarcarse() {
        UUID t = newTenant();
        consume(recibida(t, UUID.randomUUID()));

        assertThrows(RuntimeException.class, () -> jdbc.sql(
                "update audit_entries set event_type = 'x' where tenant_id = :t").param("t", t).update());
        assertThrows(RuntimeException.class, () -> jdbc.sql(
                "update audit_entries set payload = CAST('{}' AS JSONB) where tenant_id = :t").param("t", t).update());
        assertThrows(RuntimeException.class, () -> jdbc.sql(
                "delete from audit_entries where tenant_id = :t").param("t", t).update());
        assertThrows(RuntimeException.class, () -> jdbc.sql("truncate table audit_entries").update());

        assertEquals(1, jdbc.sql("update audit_entries set worm_anchored = true where tenant_id = :t")
                .param("t", t).update());
        assertThrows(RuntimeException.class, () -> jdbc.sql(
                "update audit_entries set worm_anchored = false where tenant_id = :t").param("t", t).update());
    }
}
