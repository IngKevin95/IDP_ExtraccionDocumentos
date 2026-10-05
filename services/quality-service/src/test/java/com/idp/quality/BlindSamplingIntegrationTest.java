package com.idp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
        assertThat(pending.get("document_ids").get(0).asText()).isEqualTo(doc.toString());

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
}
