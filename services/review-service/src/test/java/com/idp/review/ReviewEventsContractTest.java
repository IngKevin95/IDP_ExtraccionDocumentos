package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.ReviewEvents;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Todo evento que publica el servicio cumple su JSON Schema versionado (SEC-050, sin PII). */
class ReviewEventsContractTest {

    private final EventSerde serde = new EventSerde();
    private final EventSchemaValidator validator = new EventSchemaValidator(serde);
    private final List<EventEnvelope> published = new ArrayList<>();
    private final ReviewEvents events = new ReviewEvents((key, e) -> {
        validator.validate(e);
        published.add(e);
    });

    private ReviewTask task() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ReviewTask(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.APPROVED, null, null, "ana", "beto", true, now, 0, null, now, now, now);
    }

    @Test
    void ac03_aprobadoSimpleCumpleElEsquema() {
        events.completada(task(), ReviewEvents.APROBADO, "ana", null, false);

        assertThat(published).hasSize(1);
        var flat = serde.toFlatNode(published.get(0));
        assertThat(flat.path("action").asText()).isEqualTo("APROBADO");
        assertThat(flat.path("criticalCorrection").asBoolean()).isFalse();
        assertThat(flat.has("secondReviewerId")).isFalse();
    }

    @Test
    void ac05_aprobadoConCuatroOjosLlevaAmbosRevisoresYCriticalCorrection() {
        events.completada(task(), ReviewEvents.APROBADO, "ana", "beto", true);

        var flat = serde.toFlatNode(published.get(0));
        assertThat(flat.path("reviewerId").asText()).isEqualTo("ana");
        assertThat(flat.path("secondReviewerId").asText()).isEqualTo("beto");
        assertThat(flat.path("criticalCorrection").asBoolean()).isTrue();
    }

    @Test
    void ac07_rechazadoCumpleElEsquema() {
        events.completada(task(), ReviewEvents.RECHAZADO, "ana", null, false);

        assertThat(serde.toFlatNode(published.get(0)).path("action").asText()).isEqualTo("RECHAZADO");
    }

    private static com.idp.review.domain.Correction corr(String field, String original, String corrected) {
        return new com.idp.review.domain.Correction(UUID.randomUUID(), UUID.randomUUID(), field, original, corrected,
                false, OffsetDateTime.now(), "ana");
    }

    @Test
    void ac03_completadaLlevaTipologiaMuestreoCiegoYCamposCorregidosSinValores() {
        events.completada(task(), ReviewEvents.APROBADO, "ana", null, false, "EC", true, List.of(
                corr("Direccion Notif", null, "Calle 1"), corr("monto_numeros", "1.500", "1500"),
                corr("juzgado", "Civil 3", "Civil 5"), corr("ciudad", "Bogota", ""), corr("nit", "9", "9")));

        var flat = serde.toFlatNode(published.get(0));
        assertThat(flat.path("typology").asText()).isEqualTo("EC");
        assertThat(flat.path("blindSample").asBoolean()).isTrue();
        var cf = flat.path("correctedFields");
        assertThat(cf).hasSize(4);
        assertThat(cf.get(0).path("field").asText()).isEqualTo("direccion_notif");
        assertThat(cf.get(0).path("correctionType").asText()).isEqualTo("OMISION");
        assertThat(cf.get(1).path("correctionType").asText()).isEqualTo("FORMATO");
        assertThat(cf.get(2).path("correctionType").asText()).isEqualTo("VALOR");
        assertThat(cf.get(3).path("correctionType").asText()).isEqualTo("SOBRANTE");
        assertThat(flat.toString()).doesNotContain("Calle 1").doesNotContain("Civil").doesNotContain("Bogota");
    }

    @Test
    void ac10_escaladaCumpleElEsquemaYNoLlevaPii() {
        ReviewTask t = task();
        OffsetDateTime breached = OffsetDateTime.now().minusMinutes(3);
        events.escalada(new ReviewTask(t.id(), t.tenantId(), t.documentId(), t.correlationId(), TaskStatus.PENDING,
                null, null, null, null, false, OffsetDateTime.now(), 2, OffsetDateTime.now(), null,
                OffsetDateTime.now(), OffsetDateTime.now()), breached);

        var flat = serde.toFlatNode(published.get(0));
        assertThat(flat.path("eventType").asText()).isEqualTo("revision.escalada");
        assertThat(flat.path("level").asInt()).isEqualTo(2);
        assertThat(flat.path("taskId").asText()).isEqualTo(t.id().toString());
        assertThat(flat.has("slaBreachedAt")).isTrue();
        assertThat(flat.has("reviewerId")).isFalse();
    }

    @Test
    void laParticionEsElDocumentoYLaCorrelacionSeConserva() {
        ReviewTask t = task();
        List<String> keys = new ArrayList<>();
        new ReviewEvents((key, e) -> keys.add(key)).completada(t, ReviewEvents.APROBADO, "ana", null, false);

        assertThat(keys).containsExactly(t.documentId().toString());
        events.completada(t, ReviewEvents.APROBADO, "ana", null, false);
        assertThat(published.get(0).correlationId()).isEqualTo(t.correlationId());
        assertThat(published.get(0).tenantId().toString()).isEqualTo(t.tenantId());
    }

    @Test
    void sec050_unEventoConDatosDelDocumentoOAccionInventadaSeRechaza() {
        events.completada(task(), ReviewEvents.APROBADO, "ana", null, false);
        ObjectNode withPii = serde.toFlatNode(published.get(0)).deepCopy();
        withPii.put("monto", "1500000");
        ObjectNode badAction = serde.toFlatNode(published.get(0)).deepCopy();
        badAction.put("action", "TAL_VEZ");

        assertThatThrownBy(() -> validator.validateFlat(withPii)).isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> validator.validateFlat(badAction)).isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> events.completada(task(), "TAL_VEZ", "ana", null, false))
                .isInstanceOf(EventValidationException.class);
    }

    @Test
    void elRevisorEsObligatorio() {
        assertThatThrownBy(() -> events.completada(task(), ReviewEvents.APROBADO, "", null, false))
                .isInstanceOf(EventValidationException.class);
    }
}
