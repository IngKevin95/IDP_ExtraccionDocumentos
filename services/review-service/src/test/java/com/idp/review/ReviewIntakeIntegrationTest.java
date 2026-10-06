package com.idp.review;

import com.idp.testsupport.Topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.events.EventValidationException;
import com.idp.review.config.ReviewProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** AC-01 y AC-02: creacion idempotente de tareas desde extraccion.requiere_revision. */
class ReviewIntakeIntegrationTest extends AbstractReviewIntegrationTest {

    @Autowired ReviewProperties props;
    @Autowired com.idp.events.EventSchemaValidator validator;

    private int tasks() {
        return inTenant(tenant, () -> jdbc.queryForObject("select count(*) from review_task", Integer.class));
    }

    @Test
    void ac01_eventoValidoCreaTareaPendienteConCamposEnElSiloDelTenant() {
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        fields.program(taskId, field("direccion", 1, "[0.1,0.1,0.4,0.05]"), field("monto_numeros", 2, "[0.2,0.5,0.3,0.05]"));

        Topics.deliver(listener::onMessage, requiereRevision(tenant, UUID.randomUUID().toString(), documentId.toString(),
                taskId.toString()));

        Map<String, Object> row = inTenant(tenant, () -> jdbc.queryForMap("select * from review_task where id = ?",
                taskId));
        assertThat(row.get("STATUS")).isEqualTo("PENDING");
        assertThat(row.get("DOCUMENT_ID").toString()).isEqualTo(documentId.toString());
        assertThat(row.get("TENANT_ID").toString()).isEqualTo(tenant);
        assertThat(row.get("ASSIGNEE_ID")).isNull();
        OffsetDateTime created = (OffsetDateTime) row.get("CREATED_AT");
        assertThat(((OffsetDateTime) row.get("SLA_DUE_AT"))).isEqualTo(created.plus(props.sla()));
        List<Map<String, Object>> fieldRows = inTenant(tenant, () -> jdbc.queryForList(
                "select field_name, critical, status from review_field where task_id = ? order by field_name",
                taskId));
        assertThat(fieldRows).extracting(r -> r.get("FIELD_NAME")).containsExactly("direccion", "monto_numeros");
        assertThat(fieldRows).extracting(r -> r.get("CRITICAL")).containsExactly(false, true);
        assertThat(fieldRows).extracting(r -> r.get("STATUS")).containsOnly("PENDING");
    }

    @Test
    void ac01_tipologiaYMuestreoCiegoDelEventoQuedanEnLaTareaYSalenEnRevisionCompletada() throws Exception {
        reviewer("ana");
        UUID taskId = UUID.randomUUID();
        var n = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(requiereRevision(tenant,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), taskId.toString()));
        n.put("typology", "EC");
        n.put("blindSample", true);
        validator.validateFlat(n);
        Topics.deliver(listener::onMessage, n.toString());

        post("ana", "/v1/review/tasks/" + taskId + "/claim");
        assertThat(status(post("ana", "/v1/review/tasks/" + taskId + "/approve"))).isEqualTo(200);

        var e = outbox("revision.completada").get(0);
        assertThat(e.path("typology").asText()).isEqualTo("EC");
        assertThat(e.path("blindSample").asBoolean()).isTrue();
        validator.validateFlat(e);
    }

    @Test
    void ac01_laTareaVaSoloAlSiloDelTenantDelEvento() {
        String other = newTenant();
        UUID taskId = createTask();

        assertThat(tasks()).isEqualTo(1);
        assertThat(inTenant(other, () -> jdbc.queryForObject("select count(*) from review_task", Integer.class)))
                .isZero();
        assertThat(taskId).isNotNull();
    }

    @Test
    void ac02_mismoEventoReconsumidoNoDuplicaNiFalla() {
        UUID taskId = UUID.randomUUID();
        String json = requiereRevision(tenant, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                taskId.toString());

        Topics.deliver(listener::onMessage, json);
        Topics.deliver(listener::onMessage, json);

        assertThat(tasks()).isEqualTo(1);
    }

    @Test
    void ac02_otroEventIdConElMismoTaskIdTampocoDuplica() {
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        fields.program(taskId, field("direccion", 1, "[0.1,0.1,0.4,0.05]"));

        Topics.deliver(listener::onMessage, requiereRevision(tenant, UUID.randomUUID().toString(), documentId.toString(),
                taskId.toString()));
        Topics.deliver(listener::onMessage, requiereRevision(tenant, UUID.randomUUID().toString(), documentId.toString(),
                taskId.toString()));

        assertThat(tasks()).isEqualTo(1);
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select count(*) from review_field", Integer.class)))
                .isEqualTo(1);
    }

    @Test
    void eventoFueraDeContratoSeRechazaSinCrearTarea() {
        String sinTaskId = requiereRevision(tenant, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString()).replaceFirst("\"taskId\":\"[^\"]+\",?", "").replace(",}", "}");

        assertThatThrownBy(() -> Topics.deliver(listener::onMessage, sinTaskId)).isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> Topics.deliver(listener::onMessage, "{no es json")).isInstanceOf(EventValidationException.class);
        assertThat(tasks()).isZero();
    }

    @Test
    void otrosTiposDeEventoSeIgnoran() {
        String other = requiereRevision(tenant, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString()).replace("extraccion.requiere_revision", "documento.recibido");

        Topics.deliver(listener::onMessage, other);

        assertThat(tasks()).isZero();
    }

    @Test
    void camposDuplicadosDeLaFuenteSeColapsan() {
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.4,0.05]"), field("monto", 1, "[0.1,0.1,0.4,0.05]"));

        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select count(*) from review_field where task_id = ?",
                Integer.class, taskId))).isEqualTo(1);
    }
}
