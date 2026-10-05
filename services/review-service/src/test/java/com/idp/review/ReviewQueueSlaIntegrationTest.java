package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.review.service.SlaEscalationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Cola por campo, SLA y escalamiento, y metricas. */
class ReviewQueueSlaIntegrationTest extends AbstractReviewIntegrationTest {

    @Autowired SlaEscalationService escalation;
    @Autowired MeterRegistry meters;

    private String path(UUID taskId, String suffix) {
        return "/v1/review/tasks/" + taskId + suffix;
    }

    private void setDue(UUID taskId, OffsetDateTime due) {
        exec("update review_task set sla_due_at = ? where id = ?", due, taskId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void laColaEsPorCampoNoPorDocumento() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("direccion", 1, "[0.1,0.1,0.4,0.05]"), field("ciudad", 1, "[0.1,0.3,0.4,0.05]"),
                field("monto", 2, "[0.2,0.5,0.3,0.05]"));

        JsonNode queue = body(getAs("ana", "/v1/review/queue"));

        assertThat(queue.path("totalElements").asInt()).isEqualTo(3);
        assertThat(queue.path("content")).hasSize(3);
        for (JsonNode item : queue.path("content")) {
            assertThat(item.path("taskId").asText()).isEqualTo(taskId.toString());
            assertThat(item.path("fieldId").asText()).isNotBlank();
        }
        assertThat(queue.path("content").findValuesAsText("fieldName")).containsExactlyInAnyOrder("direccion",
                "ciudad", "monto");
        JsonNode monto = null;
        for (JsonNode item : queue.path("content")) {
            if ("monto".equals(item.path("fieldName").asText())) {
                monto = item;
            }
        }
        assertThat(monto).isNotNull();
        assertThat(monto.path("critical").asBoolean()).isTrue();
        assertThat(monto.has("originalValue")).isFalse();
    }

    @Test
    void unCampoCorregidoSaleDeLaColaYUnaTareaCerradaDesaparece() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("direccion", 1, "[0.1,0.1,0.4,0.05]"), field("ciudad", 1, "[0.1,0.3,0.4,0.05]"));
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"direccion\",\"correctedValue\":\"x\"}]");

        assertThat(body(getAs("ana", "/v1/review/queue")).path("totalElements").asInt()).isEqualTo(1);
        post("ana", path(taskId, "/approve"));
        assertThat(body(getAs("ana", "/v1/review/queue")).path("totalElements").asInt()).isZero();
    }

    @Test
    void scopeMineYUnassignedFiltranPorAsignacion() throws Exception {
        reviewer("ana");
        UUID mine = createTask(field("direccion", 1, "[0.1,0.1,0.4,0.05]"));
        createTask(field("ciudad", 1, "[0.1,0.1,0.4,0.05]"));
        post("ana", path(mine, "/claim"));

        assertThat(body(getAs("ana", "/v1/review/queue?scope=MINE")).path("totalElements").asInt()).isEqualTo(1);
        assertThat(body(getAs("ana", "/v1/review/queue?scope=UNASSIGNED")).path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(body(getAs("ana", "/v1/review/queue?scope=ALL")).path("totalElements").asInt()).isEqualTo(2);
        assertThat(body(getAs("ana", "/v1/review/tasks?mine=true")).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void laColaPaginaYOrdenaPorVencimientoConLasEscaladasPrimero() throws Exception {
        reviewer("ana");
        UUID late = createTask(field("direccion", 1, "[0.1,0.1,0.4,0.05]"));
        UUID soon = createTask(field("ciudad", 1, "[0.1,0.1,0.4,0.05]"));
        UUID escalated = createTask(field("juzgado", 1, "[0.1,0.1,0.4,0.05]"));
        setDue(late, now().plusHours(5));
        setDue(soon, now().plusHours(1));
        setDue(escalated, now().minusMinutes(5));
        escalation.escalateTenant(tenant);

        JsonNode content = body(getAs("ana", "/v1/review/queue")).path("content");

        assertThat(content.get(0).path("taskId").asText()).isEqualTo(escalated.toString());
        assertThat(content.get(0).path("escalationLevel").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("taskId").asText()).isEqualTo(soon.toString());
        assertThat(content.get(2).path("taskId").asText()).isEqualTo(late.toString());
        JsonNode page = body(getAs("ana", "/v1/review/queue?size=2&page=1")).path("content");
        assertThat(page).hasSize(1);
        assertThat(body(getAs("ana", "/v1/review/tasks?escalated=true")).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void slaVencidoEscalaUnaVezPorVencimientoYRearmaElPlazo() throws Exception {
        UUID taskId = createTask();
        setDue(taskId, now().minusMinutes(1));
        double before = meters.counter("idp_review_escalated_total").count();

        int first = escalation.escalateTenant(tenant);
        int second = escalation.escalateTenant(tenant);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select escalation_level from review_task where id = ?",
                Integer.class, taskId))).isEqualTo(1);
        OffsetDateTime due = inTenant(tenant, () -> jdbc.queryForObject(
                "select sla_due_at from review_task where id = ?", OffsetDateTime.class, taskId));
        assertThat(due).isAfter(now());
        assertThat(meters.counter("idp_review_escalated_total").count()).isEqualTo(before + 1);
    }

    @Test
    void elEscalamientoSeDetieneEnElNivelMaximoYNoToca_tareasCerradas() throws Exception {
        reviewer("ana");
        UUID open = createTask();
        UUID closed = createTask();
        post("ana", path(closed, "/reject"));
        setDue(open, now().minusMinutes(3));
        setDue(closed, now().minusMinutes(3));

        escalation.escalateTenant(tenant);
        setDue(open, now().minusMinutes(2));
        escalation.escalateTenant(tenant);
        setDue(open, now().minusMinutes(1));
        int third = escalation.escalateTenant(tenant);

        assertThat(third).isZero();
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select escalation_level from review_task where id = ?",
                Integer.class, open))).isEqualTo(2);
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select escalation_level from review_task where id = ?",
                Integer.class, closed))).isZero();
    }

    @Test
    void tareaNoVencidaNoSeEscala() {
        UUID taskId = createTask();

        assertThat(escalation.escalateTenant(tenant)).isZero();
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select escalation_level from review_task where id = ?",
                Integer.class, taskId))).isZero();
    }

    @Test
    void metricasReportanEstadosVencidasEscaladasYTiempoPromedioEnCola() throws Exception {
        reviewer("ana");
        admin("sup");
        UUID done = createTask();
        UUID overdue = createTask();
        createTask();
        post("ana", path(done, "/approve"));
        setDue(overdue, now().minusMinutes(1));
        escalation.escalateTenant(tenant);
        setDue(overdue, now().minusMinutes(1));
        exec("update review_task set cycle_seconds = 120 where id = ?", done);

        JsonNode m = body(getAs("sup", "/v1/review/metrics"));

        assertThat(m.path("tasksByStatus").path("APPROVED").asLong()).isEqualTo(1);
        assertThat(m.path("tasksByStatus").path("PENDING").asLong()).isEqualTo(2);
        assertThat(m.path("tasksByStatus").path("REJECTED").asLong()).isZero();
        assertThat(m.path("unassigned").asLong()).isEqualTo(2);
        assertThat(m.path("overdue").asLong()).isEqualTo(1);
        assertThat(m.path("escalated").asLong()).isEqualTo(1);
        assertThat(m.path("avgQueueSeconds").asDouble()).isEqualTo(120.0);
    }

    @Test
    void metricasSonDelTenantDelJwtYPidenRol() throws Exception {
        admin("sup");
        createTask();
        String other = newTenant();
        roles.grant(other, "sup2", com.idp.security.Roles.TENANT_ADMIN);

        assertThat(body(getAs(other, "sup2", "/v1/review/metrics")).path("tasksByStatus").path("PENDING").asLong())
                .isZero();
        assertThat(status(getAs("nadie", "/v1/review/metrics"))).isEqualTo(403);
    }

    @Test
    void listarTareasPaginaYFiltraPorEstado() throws Exception {
        reviewer("ana");
        for (int i = 0; i < 3; i++) {
            createTask();
        }
        UUID rejected = createTask();
        post("ana", path(rejected, "/reject"));

        JsonNode pending = body(getAs("ana", "/v1/review/tasks?status=PENDING&size=2"));
        JsonNode rejectedList = body(getAs("ana", "/v1/review/tasks?status=REJECTED"));

        assertThat(pending.path("totalElements").asInt()).isEqualTo(3);
        assertThat(pending.path("totalPages").asInt()).isEqualTo(2);
        assertThat(pending.path("content")).hasSize(2);
        assertThat(rejectedList.path("content")).hasSize(1);
        assertThat(rejectedList.path("content").get(0).path("status").asText()).isEqualTo("REJECTED");
    }
}
