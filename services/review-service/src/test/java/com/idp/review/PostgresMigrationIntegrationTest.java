package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.review.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.ReviewRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** T-03: las migraciones aplican en PostgreSQL real; restricciones de dominio y asignacion concurrente. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ReviewRepository repo;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(TenantSchemaMigrator.locationsFor(POSTGRES.getJdbcUrl())).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        repo = new ReviewRepository(jdbc);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static ReviewTask task(String tenant) {
        OffsetDateTime n = now();
        return new ReviewTask(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(), TaskStatus.PENDING,
                null, null, null, null, false, n.plusHours(4), 0, null, null, n, n);
    }

    @Test
    void migraCreaTablasDeTareasCorreccionesOutboxEIdempotencia() {
        for (String table : List.of("review_task", "review_field", "correction", "outbox", "processed_event")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isNotNull();
        }
    }

    @Test
    void ac02_elInsertCondicionalNoDuplicaLaTarea() {
        ReviewTask t = task(UUID.randomUUID().toString());

        assertThat(repo.insertTaskIfAbsent(t)).isTrue();
        assertThat(repo.insertTaskIfAbsent(t)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from review_task where id = ?", Integer.class, t.id()))
                .isEqualTo(1);
    }

    @Test
    void sec009_laBaseRechazaSegundoAprobadorIgualAlPrimeroYEstadosInvalidos() {
        ReviewTask t = task(UUID.randomUUID().toString());
        repo.insertTaskIfAbsent(t);

        assertThatThrownBy(() -> jdbc.update("update review_task set status = 'APPROVED', first_reviewer_id = 'ana', "
                + "second_reviewer_id = 'ana', critical_correction = true where id = ?", t.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update review_task set status = 'EN_REVISION' where id = ?", t.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update review_task set second_reviewer_id = 'beto' where id = ?",
                t.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("update review_task set status = 'APPROVED', first_reviewer_id = 'ana', "
                + "second_reviewer_id = 'beto', critical_correction = true where id = ?", t.id())).isEqualTo(1);
    }

    @Test
    void sec009_elUpdateCondicionalNoDejaAutoAprobar() {
        String tenant = UUID.randomUUID().toString();
        ReviewTask t = task(tenant);
        repo.insertTaskIfAbsent(t);
        assertThat(repo.firstApprove(tenant, t.id(), "ana", true, now(), 5)).isFalse();
        assertThat(repo.claim(tenant, t.id(), "ana", now())).isTrue();
        assertThat(repo.firstApprove(tenant, t.id(), "ana", true, now(), 5)).isTrue();

        assertThat(repo.secondApprove(tenant, t.id(), "ana", now(), 5)).isFalse();
        assertThat(repo.secondApprove(tenant, t.id(), "beto", now(), 5)).isTrue();
        assertThat(repo.secondApprove(tenant, t.id(), "carla", now(), 5)).isFalse();
        assertThat(jdbc.queryForObject("select second_reviewer_id from review_task where id = ?", String.class,
                t.id())).isEqualTo("beto");
    }

    @Test
    void concurrencia_dieciseisRevisoresTomanLaMismaTareaYSoloUnoGana() throws Exception {
        String tenant = UUID.randomUUID().toString();
        ReviewTask t = task(tenant);
        repo.insertTaskIfAbsent(t);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String user = "rev" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return repo.claim(tenant, t.id(), user, now());
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrencia_aprobarYRechazarALaVezCierraUnaSolaVez() throws Exception {
        String tenant = UUID.randomUUID().toString();
        ReviewTask t = task(tenant);
        repo.insertTaskIfAbsent(t);
        CountDownLatch start = new CountDownLatch(1);
        repo.claim(tenant, t.id(), "ana", now());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> approve = pool.submit(() -> {
                start.await();
                return repo.firstApprove(tenant, t.id(), "ana", false, now(), 1);
            });
            Future<Boolean> reject = pool.submit(() -> {
                start.await();
                return repo.reject(tenant, t.id(), "ana", now(), 1);
            });
            start.countDown();
            assertThat(approve.get() ^ reject.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
