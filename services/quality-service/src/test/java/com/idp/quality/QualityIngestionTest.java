package com.idp.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** quality-service spec: AC-01, AC-02, AC-03, AC-05 y consumo idempotente (T-04, T-05, T-06). */
class QualityIngestionTest extends AbstractQualityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private Map<String, Object> daily(String tenant, String tipologia) {
        return jdbc.queryForMap("select total_documentos, stp_count, hitl_count, silent_error_count, "
            + "blind_samples_total from qa_metrics_daily where tenant_id = ? and fecha = ? and tipologia = ?",
            UUID.fromString(tenant), DAY, tipologia);
    }

    @Test
    void ac02_aprobadaAutoStpIncrementaStpYTotal() {
        String tenant = UUID.randomUUID().toString();
        send(aprobada(tenant, at(DAY), "AUTO_STP", "EC"));
        send(aprobada(tenant, at(DAY), "AUTO_STP", "EC"));
        send(aprobada(tenant, at(DAY), "HUMAN_REVIEWER", "EC"));

        Map<String, Object> row = daily(tenant, "EC");
        assertThat(row.get("TOTAL_DOCUMENTOS")).isEqualTo(3);
        assertThat(row.get("STP_COUNT")).isEqualTo(2);
        assertThat(row.get("HITL_COUNT")).isEqualTo(0);
    }

    @Test
    void ac01_revisionConCorreccionIncrementaHitlYErrorPorCampoSinValores() {
        String tenant = UUID.randomUUID().toString();
        send(revision(tenant, at(DAY), "EC", "APROBADO", false, "monto"));
        send(revision(tenant, at(DAY), "EC", "APROBADO", false, "monto", "radicado"));

        Map<String, Object> row = daily(tenant, "EC");
        assertThat(row.get("HITL_COUNT")).isEqualTo(2);
        assertThat(row.get("TOTAL_DOCUMENTOS")).isEqualTo(0);
        List<Map<String, Object>> errors = jdbc.queryForList("select campo_modificado, tipo_correccion, origen, "
            + "cuenta from qa_field_error where tenant_id = ? order by campo_modificado", UUID.fromString(tenant));
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).containsEntry("CAMPO_MODIFICADO", "monto").containsEntry("CUENTA", 2)
            .containsEntry("ORIGEN", "HITL");
        assertThat(errors.get(1)).containsEntry("CAMPO_MODIFICADO", "radicado").containsEntry("CUENTA", 1);
    }

    @Test
    void ac01_revisionRechazadaCuentaComoDocumentoTerminal() {
        String tenant = UUID.randomUUID().toString();
        send(revision(tenant, at(DAY), "EC", "RECHAZADO", false));
        assertThat(daily(tenant, "EC")).containsEntry("TOTAL_DOCUMENTOS", 1).containsEntry("HITL_COUNT", 1);
    }

    @Test
    void ac03_muestraCiegaConCorreccionesEsErrorSilenteYNoCuentaComoHitl() {
        String tenant = UUID.randomUUID().toString();
        send(revision(tenant, at(DAY), "EC", "APROBADO", true, "monto"));
        send(revision(tenant, at(DAY), "EC", "APROBADO", true));

        Map<String, Object> row = daily(tenant, "EC");
        assertThat(row.get("BLIND_SAMPLES_TOTAL")).isEqualTo(2);
        assertThat(row.get("SILENT_ERROR_COUNT")).isEqualTo(1);
        assertThat(row.get("HITL_COUNT")).isEqualTo(0);
        assertThat(jdbc.queryForObject("select origen from qa_field_error where tenant_id = ?", String.class,
            UUID.fromString(tenant))).isEqualTo("BLIND");
    }

    @Test
    void ac05_eventoConValoresCrudosSeRechazaYNoSeAlmacenaNada() {
        String tenant = UUID.randomUUID().toString();
        ObjectNode e = revision(tenant, at(DAY), "EC", "APROBADO", false, "monto");
        ((ObjectNode) e.get("correctedFields").get(0)).put("valorCorregido", "Juan Perez CC 1234567890");
        assertThatThrownBy(() -> send(e)).isInstanceOf(EventValidationException.class);

        ObjectNode withRaw = aprobada(tenant, at(DAY), "AUTO_STP", "EC").put("valorExtraido", "Juan Perez");
        assertThatThrownBy(() -> send(withRaw)).isInstanceOf(EventValidationException.class);

        ObjectNode piiName = revision(tenant, at(DAY), "EC", "APROBADO", false, "monto");
        ((ObjectNode) piiName.get("correctedFields").get(0)).put("field", "Juan Perez 1234567890");
        assertThatThrownBy(() -> send(piiName)).isInstanceOf(EventValidationException.class);

        UUID t = UUID.fromString(tenant);
        assertThat(countRows("select count(*) from qa_metrics_daily where tenant_id = ?", t)).isZero();
        assertThat(countRows("select count(*) from qa_field_error where tenant_id = ?", t)).isZero();
        assertThat(countRows("select count(*) from processed_event where tenant_id = ?", t)).isZero();
    }

    @Test
    void ac05_ningunaTablaDeCalidadContieneCadenasDelDocumento() {
        String tenant = UUID.randomUUID().toString();
        send(revision(tenant, at(DAY), "EC", "APROBADO", false, "monto"));
        send(aprobada(tenant, at(DAY), "AUTO_STP", "EC"));
        UUID t = UUID.fromString(tenant);
        // Todo lo almacenado son contadores, tipologia y nombres de campo de una lista cerrada.
        assertThat(jdbc.queryForList("select campo_modificado from qa_field_error where tenant_id = ?",
            String.class, t)).containsOnly("monto");
        assertThat(jdbc.queryForList("select tipologia from qa_metrics_daily where tenant_id = ?", String.class, t))
            .containsOnly("EC");
    }

    @Test
    void eventoDuplicadoSeProcesaUnaSolaVez() {
        String tenant = UUID.randomUUID().toString();
        ObjectNode e = aprobada(tenant, at(DAY), "AUTO_STP", "EC");
        send(e);
        send(e);
        assertThat(daily(tenant, "EC")).containsEntry("TOTAL_DOCUMENTOS", 1).containsEntry("STP_COUNT", 1);
    }

    @Test
    void completadaRegistraCostoYLatenciaSinIdentificadorDeDocumento() {
        String tenant = UUID.randomUUID().toString();
        send(event("extraccion.completada", tenant, UUID.randomUUID(), at(DAY), "typology", "EC")
            .put("latencyMs", 1200).put("costMicros", 3500).put("modelPromptKey", "modelo-a/prompt-v1"));
        send(event("extraccion.completada", tenant, UUID.randomUUID(), at(DAY), "typology", "EC"));

        assertThat(countRows("select count(*) from qa_extraction_sample where tenant_id = ?",
            UUID.fromString(tenant))).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select latency_ms, cost_micros, model_prompt_key "
            + "from qa_extraction_sample where tenant_id = ?", UUID.fromString(tenant));
        assertThat(row).containsEntry("LATENCY_MS", 1200).containsEntry("MODEL_PROMPT_KEY", "modelo-a/prompt-v1");
    }

    @Test
    void eventosAjenosSeIgnoranSinValidarlos() {
        consumer.onMessage("{\"eventType\":\"documento.recibido\",\"schemaVersion\":1}");
    }
}
