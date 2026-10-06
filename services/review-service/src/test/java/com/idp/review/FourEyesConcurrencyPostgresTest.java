package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.idp.review.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.review.config.ReviewProperties;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.ReviewEvents;
import com.idp.review.infra.ReviewRepository;
import com.idp.review.service.BlindReviewPolicy;
import com.idp.review.service.Caller;
import com.idp.review.service.CriticalFields;
import com.idp.review.service.ReviewTaskService;
import com.idp.review.service.ReviewTaskService.CorrectionInput;
import com.idp.security.RoleAssignmentVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * SEC-009 bajo concurrencia sobre PostgreSQL real: un solo revisor lanza approve y una correccion critica en paralelo.
 * Nunca puede quedar APPROVED con una correccion critica sin segunda aprobacion.
 */
@Testcontainers(disabledWithoutDocker = true)
class FourEyesConcurrencyPostgresTest {

    private static final int ROUNDS = 150;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ReviewRepository repo;
    private static ReviewTaskService service;

    @BeforeAll
    static void setUp() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(TenantSchemaMigrator.locationsFor(POSTGRES.getJdbcUrl())).load().migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        repo = new ReviewRepository(jdbc);
        ReviewProperties props = new ReviewProperties("documents", "https://x.example", "s".repeat(32),
                Duration.ofSeconds(60), Duration.ofHours(4), Duration.ofHours(1), 3, List.of("monto"), "none", false,
                1024, 2, 30, 40_000_000L, new ReviewProperties.Relay(false, Duration.ofSeconds(1)),
                new ReviewProperties.Escalation(false, Duration.ofMinutes(1)));
        RoleAssignmentVerifier roles = (tenant, user, role) -> true;
        service = new ReviewTaskService(repo, mock(ReviewEvents.class), new CriticalFields(props), roles,
                mock(BlindReviewPolicy.class), new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    @Test
    void sec009_approveYCorreccionCriticaEnParaleloNuncaDejanAprobadoSinSegundaAprobacion() throws Exception {
        String tenant = UUID.randomUUID().toString();
        Caller ana = new Caller(tenant, "ana", Set.of("REVISOR"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int secondApproval = 0;
        int approvedClean = 0;
        try {
            for (int i = 0; i < ROUNDS; i++) {
                UUID taskId = newTask(tenant);
                assertThat(repo.claim(tenant, taskId, "ana", OffsetDateTime.now(ZoneOffset.UTC))).isTrue();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> approve = pool.submit(() -> {
                    start.await();
                    try {
                        service.approve(ana, taskId);
                    } catch (RuntimeException expectedWhenLosingTheRace) {
                        // la otra operacion cerro primero: solo importa el estado final
                    }
                    return null;
                });
                Future<?> correct = pool.submit(() -> {
                    start.await();
                    try {
                        service.addCorrections(ana, taskId, List.of(new CorrectionInput("monto_total", null, "9999")));
                    } catch (RuntimeException expectedWhenLosingTheRace) {
                        // la tarea ya estaba cerrada o pendiente de segunda aprobacion
                    }
                    return null;
                });
                start.countDown();
                approve.get();
                correct.get();

                Map<String, Object> row = jdbc.queryForMap("select status, critical_correction, "
                        + "(select count(*) from correction c where c.task_id = t.id and c.is_critical) as crit "
                        + "from review_task t where t.id = ?", taskId);
                String status = (String) row.get("status");
                long critical = ((Number) row.get("crit")).longValue();
                if ("APPROVED".equals(status)) {
                    assertThat(critical).as("ronda %d: APPROVED con correccion critica sin segunda aprobacion", i)
                            .isZero();
                    approvedClean++;
                } else {
                    assertThat(status).isEqualTo("PENDING_SECOND_APPROVAL");
                    assertThat(row.get("critical_correction")).isEqualTo(true);
                    assertThat(critical).isEqualTo(1);
                    secondApproval++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(secondApproval + approvedClean).isEqualTo(ROUNDS);
    }

    private static UUID newTask(String tenant) {
        OffsetDateTime n = OffsetDateTime.now(ZoneOffset.UTC);
        ReviewTask t = new ReviewTask(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.PENDING, null, null, null, null, false, n.plusHours(4), 0, null, null, n, n);
        repo.insertTaskIfAbsent(t);
        return t.id();
    }
}
