package com.idp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventSchemaValidator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Muestreo ciego con tasa 100 %: el auto-aprobado queda seleccionado y su revision cuenta como ciega (AC-03). */
@TestPropertySource(properties = "quality.blind-sampling.rate=1")
class BlindSamplingIntegrationTest extends AbstractQualityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Test
    void ac03_autoAprobadoSeleccionadoYSuRevisionSeContabilizaComoCiega() throws Exception {
        String tenant = UUID.randomUUID().toString();
        UUID doc = UUID.randomUUID();
        send(event("extraccion.aprobada", tenant, doc, at(DAY), "approvedBy", "AUTO_STP", "typology", "EC"));
        UUID t = UUID.fromString(tenant);
        assertThat(countRows("select count(*) from qa_blind_sample where tenant_id = ? and status = 'PENDING'", t))
            .isEqualTo(1);

        String user = steward(tenant);
        JsonNode pending = body(mvc.perform(get("/v1/quality/blind-samples/pending").with(token(tenant, user)))
            .andExpect(status().isOk()).andReturn());
        // SEC-051: solo agregados; ni documentId ni sampleId llegan al Data Steward.
        assertThat(pending.get("pending").asInt()).isEqualTo(1);
        assertThat(pending.get("reviewed").asInt()).isZero();
        assertThat(pending.get("pending_by_age").get("lt_1d").asInt()).isEqualTo(1);
        assertThat(pending.toString()).doesNotContain(doc.toString()).doesNotContain("document_ids");
        assertThat(pending.fieldNames()).toIterable().containsExactlyInAnyOrder("pending", "reviewed",
            "pending_by_age");

        // La revision llega sin la marca blindSample: la seleccion registrada la identifica.
        ObjectNode review = event("revision.completada", tenant, doc, at(DAY), "taskId", UUID.randomUUID().toString(),
            "action", "APROBADO", "reviewerId", "revisor-1", "typology", "EC");
        review.putArray("correctedFields").addObject().put("field", "monto").put("correctionType", "VALOR");
        send(review);

        assertThat(jdbc.queryForMap("select blind_samples_total, silent_error_count, hitl_count, stp_count "
            + "from qa_metrics_daily where tenant_id = ? and tipologia = 'EC'", t))
            .containsEntry("BLIND_SAMPLES_TOTAL", 1).containsEntry("SILENT_ERROR_COUNT", 1)
            .containsEntry("HITL_COUNT", 0).containsEntry("STP_COUNT", 1);
        assertThat(countRows("select count(*) from qa_blind_sample where tenant_id = ? and status = 'REVIEWED'", t))
            .isEqualTo(1);
    }

    @Test
    void aprobadaHumanaNoEntraAlMuestreo() {
        String tenant = UUID.randomUUID().toString();
        send(aprobada(tenant, at(DAY), "HUMAN_REVIEWER", "EC"));
        assertThat(countRows("select count(*) from qa_blind_sample where tenant_id = ?", UUID.fromString(tenant)))
            .isZero();
    }

    @Autowired EventSchemaValidator validator;

    private List<JsonNode> outbox() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String payload : jdbc.queryForList("select payload from outbox where event_type = "
            + "'calidad.muestra_ciega_solicitada' order by seq", String.class)) {
            out.add(JSON.readTree(payload));
        }
        return out;
    }

    @Test
    void ac04_seleccionPublicaPorOutboxCalidadMuestraCiegaSolicitadaConformeAlSchemaYSinPii() throws Exception {
        jdbc.update("delete from outbox");
        String tenant = UUID.randomUUID().toString();
        UUID doc = UUID.randomUUID();
        send(event("extraccion.aprobada", tenant, doc, at(DAY), "approvedBy", "AUTO_STP", "typology", "EC"));

        List<JsonNode> events = outbox();
        assertThat(events).hasSize(1);
        JsonNode e = events.get(0);
        validator.validateFlat(e);
        assertThat(e.path("tenantId").asText()).isEqualTo(tenant);
        assertThat(e.path("documentId").asText()).isEqualTo(doc.toString());
        assertThat(e.path("typology").asText()).isEqualTo("EC");
        assertThat(e.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId", "eventType", "schemaVersion",
            "occurredAt", "tenantId", "correlationId", "documentId", "typology", "sampleId");
        assertThat(jdbc.queryForObject("select cast(sample_id as varchar(36)) from qa_blind_sample where "
            + "tenant_id = ? and document_id = ?", String.class, UUID.fromString(tenant), doc))
            .isEqualTo(e.path("sampleId").asText());
        assertThat(jdbc.queryForObject("select partition_key from outbox where event_type = "
            + "'calidad.muestra_ciega_solicitada'", String.class)).isEqualTo(doc.toString());
    }

    @Test
    void ac04_reentregaDeLaAprobacionNoSeleccionaNiPublicaDosVeces() throws Exception {
        jdbc.update("delete from outbox");
        String tenant = UUID.randomUUID().toString();
        UUID doc = UUID.randomUUID();
        send(event("extraccion.aprobada", tenant, doc, at(DAY), "approvedBy", "AUTO_STP", "typology", "EC"));
        send(event("extraccion.aprobada", tenant, doc, at(DAY), "approvedBy", "AUTO_STP", "typology", "EC"));

        assertThat(outbox()).hasSize(1);
    }

    @Test
    void ac04_aprobadaHumanaNoPublicaMuestraCiega() throws Exception {
        jdbc.update("delete from outbox");
        send(aprobada(UUID.randomUUID().toString(), at(DAY), "HUMAN_REVIEWER", "EC"));

        assertThat(outbox()).isEmpty();
    }

    @Test
    void ac03_acuerdoYDesacuerdoDeLaRevisionCiegaCambianElReporteDeErrorSilente() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String user = steward(tenant);
        UUID agree = UUID.randomUUID();
        UUID disagree = UUID.randomUUID();
        send(event("extraccion.aprobada", tenant, agree, at(DAY), "approvedBy", "AUTO_STP", "typology", "EC"));
        send(event("extraccion.aprobada", tenant, disagree, at(DAY), "approvedBy", "AUTO_STP", "typology", "EC"));

        // Acuerdo: el revisor ciego transcribio lo mismo que extrajo el modelo (sin campos corregidos).
        ObjectNode ok = event("revision.completada", tenant, agree, at(DAY), "taskId", UUID.randomUUID().toString(),
            "action", "APROBADO", "reviewerId", "revisor-ciego", "typology", "EC");
        ok.put("blindSample", true);
        send(ok);
        JsonNode r1 = silentError(tenant, user);
        assertThat(r1.get("data").get(0).get("blind_samples_total").asInt()).isEqualTo(1);
        assertThat(r1.get("data").get(0).get("silent_errors_found").asInt()).isZero();
        assertThat(r1.get("data").get(0).get("silent_error_percentage").asDouble()).isZero();

        // Desacuerdo: el revisor transcribio un valor distinto => error silente.
        ObjectNode bad = event("revision.completada", tenant, disagree, at(DAY), "taskId",
            UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "revisor-ciego", "typology", "EC");
        bad.put("blindSample", true);
        bad.putArray("correctedFields").addObject().put("field", "monto").put("correctionType", "VALOR");
        send(bad);
        JsonNode r2 = silentError(tenant, user);
        assertThat(r2.get("data").get(0).get("blind_samples_total").asInt()).isEqualTo(2);
        assertThat(r2.get("data").get(0).get("silent_errors_found").asInt()).isEqualTo(1);
        assertThat(r2.get("data").get(0).get("silent_error_percentage").asDouble()).isEqualTo(50.0);
        assertThat(countRows("select count(*) from qa_field_error where tenant_id = ? and origen = 'BLIND' and "
            + "campo_modificado = 'monto'", UUID.fromString(tenant))).isEqualTo(1);
        assertThat(countRows("select count(*) from qa_blind_sample where tenant_id = ? and status = 'PENDING'",
            UUID.fromString(tenant))).isZero();
    }

    private JsonNode silentError(String tenant, String user) throws Exception {
        return body(mvc.perform(get("/v1/quality/reports/silent-error?start_date=" + DAY + "&end_date=" + DAY)
            .with(token(tenant, user))).andExpect(status().isOk()).andReturn());
    }
}
