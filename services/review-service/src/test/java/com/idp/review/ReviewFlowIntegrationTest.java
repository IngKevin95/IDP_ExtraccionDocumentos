package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** AC-03 a AC-08 y reglas de rol: flujo de aprobacion, cuatro ojos (SEC-009), rechazo y aislamiento de tenants. */
class ReviewFlowIntegrationTest extends AbstractReviewIntegrationTest {

    @Autowired EventSerde serde;
    @Autowired com.idp.security.CachingRoleAssignmentVerifier verifier;
    @Autowired EventSchemaValidator validator;

    private static final String NON_CRITICAL = "[{\"fieldName\":\"direccion\",\"correctedValue\":\"Calle 1 # 2-3\"}]";
    private static final String CRITICAL = "[{\"fieldName\":\"monto\",\"correctedValue\":\"1500000\"}]";

    private String path(UUID taskId, String suffix) {
        return "/v1/review/tasks/" + taskId + suffix;
    }

    private void assertEventMatchesSchema(JsonNode payload) {
        // El outbox guarda el JSON plano; debe cumplir el JSON Schema versionado (SEC-050).
        validator.validateFlat(payload);
    }

    @Test
    void ac03_aprobacionSinCamposCriticosCierraLaTareaYEmiteRevisionCompletada() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("direccion", 1, "[0.1,0.1,0.4,0.05]"));
        assertThat(status(postJson("ana", path(taskId, "/corrections"), NON_CRITICAL))).isEqualTo(200);

        post("ana", path(taskId, "/claim"));
        MvcResult r = post("ana", path(taskId, "/approve"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).path("status").asText()).isEqualTo("APPROVED");
        assertThat(body(r).path("firstReviewerId").asText()).isEqualTo("ana");
        assertThat(taskStatus(taskId)).isEqualTo("APPROVED");
        var events = outbox("revision.completada");
        assertThat(events).hasSize(1);
        JsonNode e = events.get(0);
        assertThat(e.path("action").asText()).isEqualTo("APROBADO");
        assertThat(e.path("reviewerId").asText()).isEqualTo("ana");
        assertThat(e.path("criticalCorrection").asBoolean()).isFalse();
        assertThat(e.has("secondReviewerId")).isFalse();
        assertThat(e.path("correctedFields")).hasSize(1);
        assertThat(e.path("correctedFields").get(0).path("field").asText()).isEqualTo("direccion");
        assertThat(e.path("correctedFields").get(0).path("correctionType").asText()).isEqualTo("VALOR");
        assertThat(e.toString()).doesNotContain("Calle 1");
        assertThat(e.path("taskId").asText()).isEqualTo(taskId.toString());
        assertThat(e.path("tenantId").asText()).isEqualTo(tenant);
        assertEventMatchesSchema(e);
        // el campo corregido queda CORRECTED
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select status from review_field where task_id = ?", String.class, taskId))).isEqualTo("CORRECTED");
    }

    @Test
    void ac03_aprobarSinCorreccionesTambienCierraYConfirmaLosCamposPendientes() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("ciudad", 1, "[0.1,0.1,0.4,0.05]"));

        post("ana", path(taskId, "/claim"));
        MvcResult r = post("ana", path(taskId, "/approve"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).path("status").asText()).isEqualTo("APPROVED");
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select status from review_field where task_id = ?", String.class, taskId))).isEqualTo("CONFIRMED");
    }

    @Test
    void ac04_correccionDeCampoCriticoExigeSegundaAprobacionYNoEmiteEvento() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.4,0.05]"));
        postJson("ana", path(taskId, "/corrections"), CRITICAL);

        post("ana", path(taskId, "/claim"));
        MvcResult r = post("ana", path(taskId, "/approve"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).path("status").asText()).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(body(r).path("firstReviewerId").asText()).isEqualTo("ana");
        assertThat(body(r).path("criticalCorrection").asBoolean()).isTrue();
        assertThat(body(r).path("assigneeId").isNull()).isTrue();
        assertThat(outbox("revision.completada")).isEmpty();
    }

    @Test
    void ac05_segundaAprobacionDeOtroRevisorCierraYEmiteEventoConAmbosRevisores() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));

        MvcResult r = post("beto", path(taskId, "/approve-secondary"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).path("status").asText()).isEqualTo("APPROVED");
        assertThat(body(r).path("secondReviewerId").asText()).isEqualTo("beto");
        var events = outbox("revision.completada");
        assertThat(events).hasSize(1);
        JsonNode e = events.get(0);
        assertThat(e.path("action").asText()).isEqualTo("APROBADO");
        assertThat(e.path("reviewerId").asText()).isEqualTo("ana");
        assertThat(e.path("secondReviewerId").asText()).isEqualTo("beto");
        assertThat(e.path("criticalCorrection").asBoolean()).isTrue();
        assertEventMatchesSchema(e);
    }

    @Test
    void ac06_elPrimerRevisorNoPuedeAutoAprobarYElEstadoNoCambia() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));

        MvcResult r = post("ana", path(taskId, "/approve-secondary"));

        assertThat(status(r)).isEqualTo(403);
        assertThat(body(r).path("code").asText()).isEqualTo("REVIEW_FOUR_EYES_VIOLATION");
        assertThat(taskStatus(taskId)).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(outbox("revision.completada")).isEmpty();
    }

    @Test
    void ac06_elPrimerRevisorTampocoToma_niSeLeReasignaLaSegundaAprobacion() throws Exception {
        reviewer("ana");
        reviewer("beto");
        admin("sup");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));

        assertThat(status(post("ana", path(taskId, "/claim")))).isEqualTo(403);
        assertThat(status(postJson("sup", path(taskId, "/reassign"), "{\"assigneeId\":\"ana\"}"))).isEqualTo(403);
        assertThat(status(postJson("sup", path(taskId, "/reassign"), "{\"assigneeId\":\"beto\"}"))).isEqualTo(200);
        assertThat(status(post("beto", path(taskId, "/approve-secondary")))).isEqualTo(200);
    }

    @Test
    void ac06_elRolDelPrimerRevisorDebeSeguirVigenteAlSegundoPaso() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));
        roles.revoke(tenant, "ana", "REVISOR");
        verifier.invalidateUser(tenant, "ana"); // lo que hace acceso.revocado sobre la cache de roles

        MvcResult r = post("beto", path(taskId, "/approve-secondary"));

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r).path("code").asText()).isEqualTo("REVIEW_FIRST_REVIEWER_INVALID");
        assertThat(taskStatus(taskId)).isEqualTo("PENDING_SECOND_APPROVAL");
    }

    @Test
    void ac06_elSegundoAprobadorDebeTenerElRolRevisorVigente() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));

        assertThat(status(post("intruso", path(taskId, "/approve-secondary")))).isEqualTo(403);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING_SECOND_APPROVAL");
    }

    @Test
    void ac07_rechazoEnPendienteEmiteRechazado() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();

        post("ana", path(taskId, "/claim"));
        MvcResult r = post("ana", path(taskId, "/reject"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).path("status").asText()).isEqualTo("REJECTED");
        JsonNode e = outbox("revision.completada").get(0);
        assertThat(e.path("action").asText()).isEqualTo("RECHAZADO");
        assertThat(e.path("reviewerId").asText()).isEqualTo("ana");
        assertThat(e.path("criticalCorrection").asBoolean()).isFalse();
        assertEventMatchesSchema(e);
    }

    @Test
    void ac07_rechazoEnSegundaAprobacionPorElSegundoRevisorEmiteRechazado() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask();
        postJson("ana", path(taskId, "/corrections"), CRITICAL);
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));

        MvcResult r = post("beto", path(taskId, "/reject"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(taskStatus(taskId)).isEqualTo("REJECTED");
        JsonNode e = outbox("revision.completada").get(0);
        assertThat(e.path("action").asText()).isEqualTo("RECHAZADO");
        assertThat(e.path("reviewerId").asText()).isEqualTo("beto");
        assertThat(e.path("criticalCorrection").asBoolean()).isFalse();
        assertThat(e.has("secondReviewerId")).isFalse();
        assertEventMatchesSchema(e);
    }

    @Test
    void ac07_unaTareaCerradaNoSeApruebaNiSeRechazaDeNuevo() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/reject"));

        assertThat(status(post("ana", path(taskId, "/approve")))).isEqualTo(400);
        assertThat(status(post("ana", path(taskId, "/reject")))).isEqualTo(400);
        assertThat(status(post("ana", path(taskId, "/approve-secondary")))).isEqualTo(400);
        assertThat(outbox("revision.completada")).hasSize(1);
    }

    @Test
    void ac08_unaTareaDeOtroTenantDa404EnTodosLosEndpoints() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.4,0.05]"));
        String other = newTenant();
        reviewer(other, "ana");
        reviewer(other, "zoe");
        UUID fieldId = inTenant(tenant, () -> jdbc.queryForObject("select id from review_field where task_id = ?",
                UUID.class, taskId));

        assertThat(status(getAs(other, "zoe", path(taskId, "")))).isEqualTo(404);
        assertThat(status(getAs(other, "zoe", path(taskId, "/corrections")))).isEqualTo(404);
        assertThat(status(getAs(other, "zoe", path(taskId, "/fields")))).isEqualTo(404);
        assertThat(status(getAs(other, "zoe", path(taskId, "/fields/" + fieldId + "/crop-link")))).isEqualTo(404);
        assertThat(status(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(path(taskId, "/approve")).with(token(other, "zoe"))).andReturn())).isEqualTo(404);
        assertThat(status(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(path(taskId, "/claim")).with(token(other, "zoe"))).andReturn())).isEqualTo(404);
        MvcResult list = getAs(other, "zoe", "/v1/review/tasks");
        assertThat(body(list).path("content")).isEmpty();
        assertThat(body(getAs(other, "zoe", "/v1/review/queue")).path("content")).isEmpty();
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
    }

    @Test
    void ac08_elTenantSaleSoloDelJwt_unRolDeOtroTenantNoServe() throws Exception {
        UUID taskId = createTask();
        String other = newTenant();
        // ana es REVISOR solo en el tenant original; el token afirma el otro tenant.
        reviewer("ana");

        MvcResult r = getAs(other, "ana", path(taskId, ""));

        assertThat(status(r)).isEqualTo(403);
    }

    @Test
    void sinRolRevisorVigenteTodoEsDenegado() throws Exception {
        reviewer("ana");
        UUID taskId = createTask();
        roles.revoke(tenant, "ana", "REVISOR");

        assertThat(status(getAs("ana", path(taskId, "")))).isEqualTo(403);
        assertThat(status(post("ana", path(taskId, "/approve")))).isEqualTo(403);
        assertThat(status(postJson("ana", path(taskId, "/corrections"), NON_CRITICAL))).isEqualTo(403);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
    }

    @Test
    void sinTokenDa401YTokenSinTenantDa403() throws Exception {
        UUID taskId = createTask();

        assertThat(status(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path(taskId, ""))).andReturn())).isEqualTo(401);
        MvcResult noTenant = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path(taskId, "")).with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("ana")))).andReturn();
        assertThat(status(noTenant)).isEqualTo(403);
    }

    @Test
    void elEventoCompletadoNoLlevaDatosDelDocumento() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.4,0.05]"));
        postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"monto\",\"correctedValue\":\"valor-secreto-123\"}]");
        reviewer("beto");
        post("ana", path(taskId, "/claim"));
        post("ana", path(taskId, "/approve"));
        post("beto", path(taskId, "/approve-secondary"));

        String raw = inTenant(tenant, () -> jdbc.queryForObject(
                "select payload from outbox where event_type = 'revision.completada'", String.class));

        assertThat(raw).doesNotContain("valor-secreto-123").doesNotContain("fieldName").doesNotContain("correctedValue");
    }
}
