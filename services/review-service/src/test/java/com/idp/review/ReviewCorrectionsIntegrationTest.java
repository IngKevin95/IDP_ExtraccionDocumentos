package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** RF-301: correcciones por campo, criticidad decidida por el servidor y lectura para extraction-service. */
class ReviewCorrectionsIntegrationTest extends AbstractReviewIntegrationTest {

    private String path(UUID taskId, String suffix) {
        return "/v1/review/tasks/" + taskId + suffix;
    }

    @Test
    void correccionesSeGuardanConCriticidadCalculadaPorElServidor() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("monto_numeros", 1, "[0.1,0.1,0.4,0.05]"));

        MvcResult r = postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"monto_numeros\",\"correctedValue\":\"900\",\"isCritical\":false},"
                        + "{\"fieldName\":\"direccion\",\"originalValue\":\"viejo\",\"correctedValue\":\"nuevo\"}]");

        assertThat(status(r)).isEqualTo(200);
        JsonNode list = body(r);
        assertThat(list).hasSize(2);
        for (JsonNode c : list) {
            boolean critical = c.path("fieldName").asText().equals("monto_numeros");
            assertThat(c.path("isCritical").asBoolean()).isEqualTo(critical);
            assertThat(c.path("createdBy").asText()).isEqualTo("ana");
        }
        // el valor original del campo dudoso viene de la tarea cuando el revisor no lo manda
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select original_value from correction where task_id = ? and field_name = 'monto_numeros'",
                String.class, taskId))).isEqualTo("valor-monto_numeros");
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select status from review_field where task_id = ?", String.class, taskId))).isEqualTo("CORRECTED");
    }

    @Test
    void corregirDeNuevoUnCampoSobrescribeLaCorreccion() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"direccion\",\"correctedValue\":\"uno\"}]");
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"direccion\",\"correctedValue\":\"dos\"}]");

        JsonNode list = body(getAs("ana", path(taskId, "/corrections")));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("correctedValue").asText()).isEqualTo("dos");
    }

    @Test
    void tipoMedidaEsCriticoYExigeSegundaAprobacion() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"tipo_medida\",\"correctedValue\":\"EMBARGO\"}]");
        MvcResult approve = post("ana", path(taskId, "/approve"));

        assertThat(body(approve).path("status").asText()).isEqualTo("PENDING_SECOND_APPROVAL");
    }

    @Test
    void soloSeCorrigeUnaTareaPendiente() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"monto\",\"correctedValue\":\"1\"}]");
        post("ana", path(taskId, "/approve"));

        MvcResult r = postJson("beto", path(taskId, "/corrections"),
                "[{\"fieldName\":\"monto\",\"correctedValue\":\"999\"}]");

        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r).path("code").asText()).isEqualTo("REVIEW_INVALID_STATE");
    }

    @Test
    void solicitudesInvalidasDan400() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();

        assertThat(status(postJson("ana", path(taskId, "/corrections"), "[]"))).isEqualTo(400);
        assertThat(status(postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\" \",\"correctedValue\":\"x\"}]"))).isEqualTo(400);
        assertThat(status(postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"direccion\"}]"))).isEqualTo(400);
        assertThat(status(postJson("ana", path(taskId, "/corrections"), "{no es lista}"))).isEqualTo(400);
        assertThat(status(getAs("ana", "/v1/review/tasks?status=INVENTADO"))).isEqualTo(400);
        assertThat(status(getAs("ana", "/v1/review/queue?scope=NADA"))).isEqualTo(400);
    }

    @Test
    void getCorrectionsLoLeenRevisorYSupervisorPeroNoOtrosRoles() throws Exception {
        reviewer("ana");
        admin("sup");
        roles.grant(tenant, "op", com.idp.security.Roles.OPERADOR);
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"direccion\",\"correctedValue\":\"x\"}]");

        assertThat(status(getAs("ana", path(taskId, "/corrections")))).isEqualTo(200);
        assertThat(status(getAs("sup", path(taskId, "/corrections")))).isEqualTo(200);
        assertThat(status(getAs("op", path(taskId, "/corrections")))).isEqualTo(403);
    }

    @Test
    void unSupervisorNoPuedeCorregirNiAprobar() throws Exception {
        admin("sup");
        UUID taskId = createTask();

        assertThat(status(postJson("sup", path(taskId, "/corrections"),
                "[{\"fieldName\":\"direccion\",\"correctedValue\":\"x\"}]"))).isEqualTo(403);
        assertThat(status(post("sup", path(taskId, "/approve")))).isEqualTo(403);
        assertThat(status(post("sup", path(taskId, "/reject")))).isEqualTo(403);
    }
}
