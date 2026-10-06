package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventValidationException;
import com.idp.review.domain.FieldCandidate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Revision ciega de oficios auto-aprobados (fiabilidad AC-04, quality-service): calidad.muestra_ciega_solicitada crea
 * una tarea ciega; el revisor transcribe sin ver la salida del modelo ni el score; el cierre emite revision.completada
 * con blindSample=true y solo el tipo de diferencia; sin cuatro ojos, con revisor independiente.
 */
class ReviewBlindSampleIntegrationTest extends AbstractReviewIntegrationTest {

    @Autowired EventSchemaValidator validator;

    private static FieldCandidate modelField(String name, String value) {
        return new FieldCandidate(name, 1, "[0.1,0.1,0.4,0.05]", value, new BigDecimal("0.9876"));
    }

    private String muestraCiega(String tenantId, UUID documentId, UUID sampleId) {
        ObjectNode n = JSON.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", "calidad.muestra_ciega_solicitada");
        n.put("schemaVersion", 1);
        n.put("occurredAt", java.time.Instant.now().toString());
        n.put("tenantId", tenantId);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", documentId.toString());
        n.put("typology", "EC");
        n.put("sampleId", sampleId.toString());
        validator.validateFlat(n);
        return n.toString();
    }

    /** Documento auto-aprobado con salida del modelo conocida; devuelve el sampleId (= id de la tarea ciega). */
    private UUID blindTask(UUID documentId, FieldCandidate... modelOutput) {
        UUID sampleId = UUID.randomUUID();
        fields.programApproved(documentId, modelOutput);
        listener.onMessage(muestraCiega(tenant, documentId, sampleId));
        return sampleId;
    }

    private static String path(UUID taskId, String suffix) {
        return "/v1/review/tasks/" + taskId + suffix;
    }

    private int count(String sql, Object... args) {
        return inTenant(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    void acB1_eventoValidoCreaTareaCiegaPendienteConTodosLosCamposYSinEmitirEventos() {
        UUID doc = UUID.randomUUID();
        UUID sampleId = blindTask(doc, modelField("direccion", "Calle 1"), modelField("monto", "1000"));

        Map<String, Object> row = inTenant(tenant, () -> jdbc.queryForMap(
                "select status, blind_sample, typology, document_id from review_task where id = ?", sampleId));
        assertThat(row.get("STATUS")).isEqualTo("PENDING");
        assertThat(row.get("BLIND_SAMPLE")).isEqualTo(true);
        assertThat(row.get("TYPOLOGY")).isEqualTo("EC");
        assertThat(row.get("DOCUMENT_ID").toString()).isEqualTo(doc.toString());
        assertThat(count("select count(*) from review_field where task_id = ?", sampleId)).isEqualTo(2);
        // El documento ya esta aprobado: crear la tarea no publica ningun evento que cambie su estado.
        assertThat(count("select count(*) from outbox")).isZero();
    }

    @Test
    void acB1_reconsumoYMismoSampleIdConOtroEventIdNoDuplican() {
        UUID doc = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        fields.programApproved(doc, modelField("monto", "1000"));
        String first = muestraCiega(tenant, doc, sampleId);

        listener.onMessage(first);
        listener.onMessage(first);
        listener.onMessage(muestraCiega(tenant, doc, sampleId));

        assertThat(count("select count(*) from review_task")).isEqualTo(1);
        assertThat(count("select count(*) from review_field")).isEqualTo(1);
    }

    @Test
    void acB1_sinCamposExtraidosNoSeCreaTarea() {
        listener.onMessage(muestraCiega(tenant, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(count("select count(*) from review_task")).isZero();
    }

    @Test
    void acB1_eventoFueraDeContratoSeRechaza() throws Exception {
        ObjectNode n = (ObjectNode) JSON.readTree(muestraCiega(tenant, UUID.randomUUID(), UUID.randomUUID()));
        n.remove("sampleId");

        assertThatThrownBy(() -> listener.onMessage(n.toString())).isInstanceOf(EventValidationException.class);
        assertThat(count("select count(*) from review_task")).isZero();
    }

    @Test
    void acB2_elRevisorNoVeLaSalidaDelModeloNiElScoreEnNingunaVista() throws Exception {
        reviewer("ana");
        UUID doc = UUID.randomUUID();
        UUID taskId = blindTask(doc, modelField("direccion", "SECRETO-MODELO-1"), modelField("monto", "SECRETO-9"));

        MvcResult fieldsRes = getAs("ana", path(taskId, "/fields"));
        MvcResult taskRes = getAs("ana", path(taskId, ""));
        MvcResult queueRes = getAs("ana", "/v1/review/queue?scope=ALL");
        MvcResult corrRes = postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"direccion\",\"originalValue\":\"forjado\",\"correctedValue\":\"Calle 1\"}]");
        MvcResult corrList = getAs("ana", path(taskId, "/corrections"));

        for (MvcResult r : List.of(fieldsRes, taskRes, queueRes, corrRes, corrList)) {
            assertThat(status(r)).isEqualTo(200);
            String raw = r.getResponse().getContentAsString();
            assertThat(raw).doesNotContain("SECRETO").doesNotContain("0.9876").doesNotContain("forjado");
        }
        JsonNode fieldList = body(fieldsRes);
        assertThat(fieldList).hasSize(2);
        for (JsonNode f : fieldList) {
            assertThat(f.path("confidence").isNull()).isTrue();
            assertThat(f.path("hasEvidence").asBoolean()).isTrue();
            assertThat(f.path("status").asText()).isIn("PENDING", "CORRECTED");
        }
        for (JsonNode q : body(queueRes).path("content")) {
            assertThat(q.path("confidence").isNull()).isTrue();
        }
        assertThat(status(taskRes)).isEqualTo(200);
        assertThat(body(corrRes).get(0).path("originalValue").isNull()).isTrue();
        // el original guardado es el del modelo, no el que envio el cliente
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "select original_value from correction where task_id = ? and field_name = 'direccion'",
                String.class, taskId))).isEqualTo("SECRETO-MODELO-1");
    }

    @Test
    void acB2_camposDesconocidosSeRechazanEnUnaTareaCiega() throws Exception {
        reviewer("ana");
        UUID taskId = blindTask(UUID.randomUUID(), modelField("monto", "1"));

        MvcResult r = postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"inventado\",\"correctedValue\":\"x\"}]");

        assertThat(status(r)).isEqualTo(400);
        assertThat(count("select count(*) from correction")).isZero();
    }

    @Test
    void acB3_desacuerdoEmiteRevisionCompletadaCiegaSoloConNombreYTipoSinCuatroOjos() throws Exception {
        reviewer("ana");
        UUID taskId = blindTask(UUID.randomUUID(), modelField("direccion", "Calle 1"), modelField("monto", "1000"),
                modelField("ciudad", "Bogota"));

        // transcribe igual direccion, distinto monto (campo critico) y vacio ciudad (el modelo invento un valor)
        assertThat(status(postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"direccion\",\"correctedValue\":\"Calle 1\"},"
                        + "{\"fieldName\":\"monto\",\"correctedValue\":\"2000\"},"
                        + "{\"fieldName\":\"ciudad\",\"correctedValue\":\"\"}]"))).isEqualTo(200);
        MvcResult approve = post("ana", path(taskId, "/approve"));

        assertThat(status(approve)).isEqualTo(200);
        // un campo critico distinto NO pide segunda aprobacion: es medicion, no correccion
        assertThat(body(approve).path("status").asText()).isEqualTo("APPROVED");
        // el REVISOR no recibe el flag blindSample (ceguera); solo los roles de gestion
        assertThat(body(approve).path("blindSample").asBoolean()).isFalse();
        List<JsonNode> events = outbox("revision.completada");
        assertThat(events).hasSize(1);
        JsonNode e = events.get(0);
        validator.validateFlat(e);
        assertThat(e.path("blindSample").asBoolean()).isTrue();
        assertThat(e.path("criticalCorrection").asBoolean()).isFalse();
        assertThat(e.has("secondReviewerId")).isFalse();
        assertThat(e.path("typology").asText()).isEqualTo("EC");
        assertThat(e.path("taskId").asText()).isEqualTo(taskId.toString());
        assertThat(e.path("reviewerId").asText()).isEqualTo("ana");
        assertThat(e.path("correctedFields")).hasSize(2);
        assertThat(e.path("correctedFields").toString()).contains("{\"field\":\"monto\",\"correctionType\":\"VALOR\"}")
                .contains("{\"field\":\"ciudad\",\"correctionType\":\"SOBRANTE\"}")
                .doesNotContain("direccion").doesNotContain("2000").doesNotContain("1000").doesNotContain("Bogota");
    }

    @Test
    void acB3_acuerdoEmiteRevisionCompletadaCiegaSinCamposCorregidos() throws Exception {
        reviewer("ana");
        UUID taskId = blindTask(UUID.randomUUID(), modelField("direccion", "Calle 1"), modelField("monto", "1000"));

        postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"direccion\",\"correctedValue\":\"Calle 1\"},"
                        + "{\"fieldName\":\"monto\",\"correctedValue\":\"1000\"}]");
        assertThat(status(post("ana", path(taskId, "/approve")))).isEqualTo(200);

        JsonNode e = outbox("revision.completada").get(0);
        validator.validateFlat(e);
        assertThat(e.path("blindSample").asBoolean()).isTrue();
        assertThat(e.has("correctedFields")).isFalse();
    }

    @Test
    void acB3_noSeCierraSinTranscribirTodosLosCamposNiSeRechaza() throws Exception {
        reviewer("ana");
        UUID taskId = blindTask(UUID.randomUUID(), modelField("direccion", "Calle 1"), modelField("monto", "1000"));
        postJson("ana", path(taskId, "/corrections"), "[{\"fieldName\":\"direccion\",\"correctedValue\":\"Calle 1\"}]");

        MvcResult incomplete = post("ana", path(taskId, "/approve"));
        MvcResult reject = post("ana", path(taskId, "/reject"));

        assertThat(status(incomplete)).isEqualTo(400);
        assertThat(status(reject)).isEqualTo(400);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(outbox("revision.completada")).isEmpty();
    }

    @Test
    void acB4_quienCargoElDocumentoNoPuedeHacerLaRevisionCiega() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID doc = UUID.randomUUID();
        fields.uploadedBy(doc, "ana");
        UUID taskId = blindTask(doc, modelField("monto", "1000"));

        assertThat(status(post("ana", path(taskId, "/claim")))).isEqualTo(403);
        assertThat(status(postJson("ana", path(taskId, "/corrections"),
                "[{\"fieldName\":\"monto\",\"correctedValue\":\"1000\"}]"))).isEqualTo(403);
        assertThat(status(post("ana", path(taskId, "/approve")))).isEqualTo(403);
        assertThat(status(post("beto", path(taskId, "/claim")))).isEqualTo(200);
    }

    @Test
    void acB4_quienRevisoElDocumentoAntesNoPuedeHacerLaRevisionCiega() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID doc = UUID.randomUUID();
        UUID normalTask = UUID.randomUUID();
        listener.onMessage(requiereRevision(tenant, UUID.randomUUID().toString(), doc.toString(),
                normalTask.toString()));
        postJson("ana", path(normalTask, "/corrections"), "[{\"fieldName\":\"direccion\",\"correctedValue\":\"x\"}]");
        assertThat(status(post("ana", path(normalTask, "/approve")))).isEqualTo(200);
        UUID taskId = blindTask(doc, modelField("monto", "1000"));

        assertThat(status(post("ana", path(taskId, "/claim")))).isEqualTo(403);
        assertThat(status(post("beto", path(taskId, "/claim")))).isEqualTo(200);
        // un supervisor tampoco puede reasignarla a quien ya intervino
        admin("root");
        assertThat(status(postJson("root", path(taskId, "/reassign"), "{\"assigneeId\":\"ana\"}"))).isEqualTo(403);
    }

    @Test
    void acB4_unaTareaNormalNoAplicaLaRestriccionDeIndependencia() throws Exception {
        reviewer("ana");
        UUID doc = UUID.randomUUID();
        fields.uploadedBy(doc, "ana");
        UUID normalTask = UUID.randomUUID();
        listener.onMessage(requiereRevision(tenant, UUID.randomUUID().toString(), doc.toString(),
                normalTask.toString()));

        assertThat(status(post("ana", path(normalTask, "/claim")))).isEqualTo(200);
    }
}
