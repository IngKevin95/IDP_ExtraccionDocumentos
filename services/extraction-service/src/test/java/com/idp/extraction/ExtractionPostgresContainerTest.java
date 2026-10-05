package com.idp.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.extraction.store.ExtractionRepository.ExtractionRecord;
import com.idp.extraction.store.ExtractionRepository.FieldRecord;
import com.idp.extraction.store.ExtractionRepository.Status;
import com.idp.extraction.store.JdbcExtractionRepository;
import com.idp.tenant.context.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** La migracion real del silo (jsonb, trigger de inmutabilidad) sobre PostgreSQL; se omite sin Docker (T-02, AC-08). */
@Testcontainers(disabledWithoutDocker = true)
class ExtractionPostgresContainerTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void migracionCreaTablasPersisteJsonbYLasVersionesSonInmutables() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/tenant").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        JdbcExtractionRepository repo = new JdbcExtractionRepository(jdbc, new SimpleMeterRegistry(), true);
        UUID tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenant.toString());
        ExtractionRecord e = new ExtractionRecord(UUID.randomUUID(), doc, tenant, Status.COMPLETED, "EC", 1,
            "model-1", "prompt-1", new BigDecimal("0.9900"), 10, 5, new BigDecimal("0.001"), null, null, Instant.now());
        repo.save(e, List.of(new FieldRecord(UUID.randomUUID(), null, null, "radicado", "123", new BigDecimal("0.99"), 1,
            "123", "[0.1,0.2,0.3,0.4]", false, null)));

        assertThat(repo.existsFinished(doc)).isTrue();
        assertThat(repo.fields(tenant, e.id())).singleElement()
            .satisfies(f -> assertThat(f.boundingBoxJson()).contains("0.1"));
        assertThatThrownBy(() -> jdbc.update("update extraction set model_version = 'otro' where id = ?", e.id()))
            .hasMessageContaining("inmutables");
        assertThatThrownBy(() -> jdbc.update("update extraction set prompt_version = 'otro' where id = ?", e.id()))
            .hasMessageContaining("inmutables");
        assertThat(jdbc.update("update extraction set detail = 'ok' where id = ?", e.id())).isEqualTo(1);
    }
}
