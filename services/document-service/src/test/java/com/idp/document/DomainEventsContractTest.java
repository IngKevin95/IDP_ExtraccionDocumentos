package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.infra.DomainEvents;
import com.idp.events.EventEnvelope;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.EventValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T-12: todo evento que publica el servicio cumple su JSON Schema versionado de contracts/events. */
class DomainEventsContractTest {

    private final EventSchemaValidator validator = new EventSchemaValidator(new EventSerde());
    private final List<EventEnvelope> published = new ArrayList<>();
    private final DomainEvents events = new DomainEvents((key, e) -> {
        validator.validate(e);
        published.add(e);
    });

    private DocumentRecord doc() {
        OffsetDateTime now = OffsetDateTime.now();
        return new DocumentRecord(UUID.randomUUID(), UUID.randomUUID().toString(), "a".repeat(64), "EC",
                "11001400300120260012300", 1, DocumentStatus.RECIBIDO, Classification.CONFIDENCIAL,
                "documents/x/original.enc", "application/pdf", 10L, "u", null, now, now, null);
    }

    @Test
    void ac03_losSeisEventosPublicadosCumplenElEsquema() {
        DocumentRecord d = doc();
        events.recibido(d);
        events.renderizado(d, List.of("documents/x/pages/page_1.png.enc"));
        events.rechazado(d, "RENDERER_FAILED");
        events.extraccionSolicitada(d);
        events.aprobada(d, "AUTO_STP");
        events.purgado(d, Instant.now());

        assertThat(published).extracting(EventEnvelope::eventType).containsExactly("documento.recibido",
                "documento.renderizado", "documento.rechazado", "extraccion.solicitada", "extraccion.aprobada",
                "documento.purgado");
        published.forEach(e -> assertThat(e.tenantId().toString()).isEqualTo(d.tenantId()));
    }

    @Test
    void ac03_aprobadaIncluyeTipologia() {
        events.aprobada(doc(), "HUMAN_REVIEWER");
        assertThat(published.get(0).payload().path("typology").asText()).isEqualTo("EC");
    }

    @Test
    void sec050_unEventoFueraDeContratoSeRechaza() {
        DocumentRecord d = doc();
        assertThatThrownBy(() -> events.rechazado(d, "MOTIVO_INVENTADO")).isInstanceOf(EventValidationException.class);
    }
}
