package com.idp.review;

import com.idp.testsupport.Topics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hallazgos de la auditoria de seguridad: asignado obligatorio, autor de correccion, original guardado, ceguera. */
class ReviewHardeningIntegrationTest extends AbstractReviewIntegrationTest {

    private static final String CRITICAL = "[{\"fieldName\":\"monto\",\"correctedValue\":\"1500000\"}]";

    private String path(UUID taskId, String suffix) {
        return "/v1/review/tasks/" + taskId + suffix;
    }

    @Test
    void sec_noSeApruebaNiSeRechazaSinTomarLaTarea() throws Exception {
        reviewer("ana");
        UUID approveTask = createTask();
        UUID rejectTask = createTask();

        var approve = post("ana", path(approveTask, "/approve"));
        var reject = post("ana", path(rejectTask, "/reject"));

        assertThat(status(approve)).isEqualTo(409);
        assertThat(body(approve).path("code").asText()).isEqualTo("REVIEW_NOT_ASSIGNEE");
        assertThat(status(reject)).isEqualTo(409);
        assertThat(body(reject).path("code").asText()).isEqualTo("REVIEW_NOT_ASSIGNEE");
        assertThat(taskStatus(approveTask)).isEqualTo("PENDING");
        assertThat(taskStatus(rejectTask)).isEqualTo("PENDING");
        assertThat(outbox("revision.completada")).isEmpty();
    }

    @Test
    void sec_unRevisorNoApruebaLaTareaTomadaPorOtro() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        post("ana", path(taskId, "/claim"));

        assertThat(status(post("beto", path(taskId, "/approve")))).isEqualTo(409);
        assertThat(status(post("beto", path(taskId, "/reject")))).isEqualTo(409);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
    }

    @Test
    void sec_elSegundoAprobadorNoPuedeSerAutorDeUnaCorreccionDeLaTarea() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.4,0.05]"), field("ciudad", 1, "[0.1,0.2,0.4,0.05]"));
        // ana corrige un campo no critico y devuelve la tarea; beto corrige el critico y aprueba como primer revisor
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"ciudad\",\"correctedValue\":\"Cali\"}]");
        post("ana", path(taskId, "/release"));
        postJson("beto", path(taskId, "/corrections"), CRITICAL);
        assertThat(status(post("beto", path(taskId, "/approve")))).isEqualTo(200);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING_SECOND_APPROVAL");

        var second = post("ana", path(taskId, "/approve-secondary"));

        assertThat(status(second)).isEqualTo(403);
        assertThat(body(second).path("code").asText()).isEqualTo("REVIEW_FOUR_EYES_VIOLATION");
        assertThat(taskStatus(taskId)).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(outbox("revision.completada")).isEmpty();
    }

    @Test
    void sec_elSegundoAprobadorAjenoALasCorreccionesSiAprueba() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.4,0.05]"));
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/approve"));

        assertThat(status(post("beto", path(taskId, "/approve-secondary")))).isEqualTo(200);
        assertThat(taskStatus(taskId)).isEqualTo("APPROVED");
    }

    @Test
    void sec_elOriginalDeUnaTareaNormalSaleSiempreDelValorGuardadoDelCampo() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("ciudad", 1, "[0.1,0.1,0.4,0.05]"));

        var r = postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"ciudad\",\"originalValue\":\"valor-forjado\",\"correctedValue\":\"Cali\"}]");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get(0).path("originalValue").asText()).isEqualTo("valor-ciudad");
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select original_value from correction where task_id = ? and field_name = 'ciudad'", String.class,
                taskId))).isEqualTo("valor-ciudad");
    }

    @Test
    void sec_blindSampleSoloSeDevuelveARolesDeGestion() throws Exception {
        reviewer("ana");
        admin("sup");
        UUID taskId = UUID.randomUUID();
        ObjectNode n = (ObjectNode) JSON.readTree(requiereRevision(tenant, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), taskId.toString()));
        n.put("blindSample", true);
        Topics.deliver(listener::onMessage, n.toString());

        JsonNode asReviewer = body(getAs("ana", path(taskId, "")));
        JsonNode listed = body(getAs("ana", "/v1/review/tasks?status=PENDING")).path("content").get(0);
        JsonNode claimed = body(post("ana", path(taskId, "/claim")));
        JsonNode asAdmin = body(getAs("sup", path(taskId, "")));

        assertThat(asReviewer.path("blindSample").asBoolean()).isFalse();
        assertThat(listed.path("blindSample").asBoolean()).isFalse();
        assertThat(claimed.path("blindSample").asBoolean()).isFalse();
        assertThat(asAdmin.path("blindSample").asBoolean()).isTrue();
    }
}
