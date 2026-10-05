package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.review.service.Caller;
import com.idp.review.service.Exceptions.ConflictException;
import com.idp.review.service.Exceptions.FourEyesViolationException;
import com.idp.review.service.ReviewTaskService;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Asignacion sin doble asignacion (concurrencia), liberacion y reasignacion por un supervisor. */
class ReviewAssignmentIntegrationTest extends AbstractReviewIntegrationTest {

    @Autowired ReviewTaskService service;

    private String path(UUID taskId, String suffix) {
        return "/v1/review/tasks/" + taskId + suffix;
    }

    private Caller caller(String user) {
        return new Caller(tenant, user, Set.of(Roles.REVISOR));
    }

    @Test
    void claimAsignaYUnSegundoRevisorRecibe409() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();

        MvcResult first = post("ana", path(taskId, "/claim"));
        MvcResult second = post("beto", path(taskId, "/claim"));

        assertThat(status(first)).isEqualTo(200);
        assertThat(body(first).path("assigneeId").asText()).isEqualTo("ana");
        assertThat(status(second)).isEqualTo(409);
        assertThat(body(second).path("code").asText()).isEqualTo("REVIEW_TASK_ASSIGNED");
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select assignee_id from review_task where id = ?",
                String.class, taskId))).isEqualTo("ana");
    }

    @Test
    void claimEsIdempotenteParaElMismoRevisor() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();

        assertThat(status(post("ana", path(taskId, "/claim")))).isEqualTo(200);
        assertThat(status(post("ana", path(taskId, "/claim")))).isEqualTo(200);
    }

    @Test
    void unaTareaAsignadaSoloLaTrabajaSuAsignado() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        post("ana", path(taskId, "/claim"));

        assertThat(status(post("beto", path(taskId, "/approve")))).isEqualTo(409);
        assertThat(status(post("beto", path(taskId, "/reject")))).isEqualTo(409);
        assertThat(status(postJson("beto", path(taskId, "/corrections"),
                "[{\"fieldName\":\"direccion\",\"correctedValue\":\"x\"}]"))).isEqualTo(409);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(status(post("ana", path(taskId, "/approve")))).isEqualTo(200);
    }

    @Test
    void releaseDevuelveLaTareaALaColaYSoloLoHaceElAsignado() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        post("ana", path(taskId, "/claim"));

        assertThat(status(post("beto", path(taskId, "/release")))).isEqualTo(409);
        MvcResult r = post("ana", path(taskId, "/release"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).path("assigneeId").isNull()).isTrue();
        assertThat(status(post("beto", path(taskId, "/claim")))).isEqualTo(200);
    }

    @Test
    void reasignarExigeSupervisorYDestinoConRolRevisor() throws Exception {
        reviewer("ana");
        reviewer("beto");
        admin("sup");
        UUID taskId = createTask();
        post("ana", path(taskId, "/claim"));

        assertThat(status(postJson("ana", path(taskId, "/reassign"), "{\"assigneeId\":\"beto\"}"))).isEqualTo(403);
        assertThat(status(postJson("sup", path(taskId, "/reassign"), "{\"assigneeId\":\"nadie\"}"))).isEqualTo(400);
        assertThat(status(postJson("sup", path(taskId, "/reassign"), "{\"assigneeId\":\"\"}"))).isEqualTo(400);
        MvcResult ok = postJson("sup", path(taskId, "/reassign"), "{\"assigneeId\":\"beto\"}");

        assertThat(status(ok)).isEqualTo(200);
        assertThat(body(ok).path("assigneeId").asText()).isEqualTo("beto");
        assertThat(status(post("ana", path(taskId, "/approve")))).isEqualTo(409);
        assertThat(status(post("beto", path(taskId, "/approve")))).isEqualTo(200);
    }

    @Test
    void reasignarUnaTareaCerradaDa400() throws Exception {
        reviewer("ana");
        reviewer("beto");
        admin("sup");
        UUID taskId = createTask();
        post("ana", path(taskId, "/reject"));

        assertThat(status(postJson("sup", path(taskId, "/reassign"), "{\"assigneeId\":\"beto\"}"))).isEqualTo(400);
    }

    @Test
    void concurrencia_ochoRevisoresTomanLaMismaTareaYExactamenteUnoGana() throws Exception {
        UUID taskId = createTask();
        List<String> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            users.add(reviewer("rev" + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String user : users) {
                Callable<String> job = () -> {
                    TenantContextHolder.setTenantId(tenant);
                    try {
                        start.await();
                        service.claim(caller(user), taskId);
                        return "ok";
                    } catch (ConflictException e) {
                        return "conflict";
                    } finally {
                        TenantContextHolder.clear();
                    }
                };
                results.add(pool.submit(job));
            }
            start.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<String> f : results) {
                String r = f.get();
                if ("ok".equals(r)) {
                    ok++;
                } else {
                    conflict++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflict).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
        String assignee = inTenant(tenant, () -> jdbc.queryForObject(
                "select assignee_id from review_task where id = ?", String.class, taskId));
        assertThat(users).contains(assignee);
    }

    @Test
    void concurrencia_dosSegundosAprobadoresSimultaneosEmitenUnSoloEvento() throws Exception {
        reviewer("ana");
        reviewer("beto");
        reviewer("carla");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"monto\",\"correctedValue\":\"10\"}]");
        post("ana", path(taskId, "/approve"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String user : List.of("beto", "carla")) {
                results.add(pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenant);
                    try {
                        start.await();
                        service.approveSecondary(caller(user), taskId);
                        return "ok";
                    } catch (ConflictException | FourEyesViolationException
                             | com.idp.review.service.Exceptions.InvalidStateException e) {
                        return "lost";
                    } finally {
                        TenantContextHolder.clear();
                    }
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<String> f : results) {
                if ("ok".equals(f.get())) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(taskStatus(taskId)).isEqualTo("APPROVED");
        assertThat(outbox("revision.completada")).hasSize(1);
    }
}
