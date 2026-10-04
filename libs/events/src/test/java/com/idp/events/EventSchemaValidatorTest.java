package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSchemaValidatorTest {

    private final EventSerde serde = new EventSerde();
    private final EventSchemaValidator validator = new EventSchemaValidator(serde);

    @Test
    void ac06_eventoValidoCumpleElEsquemaDeContracts() {
        assertDoesNotThrow(() -> validator.validate(TestEvents.accesoRevocado(UUID.randomUUID())));
    }

    @Test
    void ac06_campoRequeridoAusenteSeRechaza() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope sinSubject = new EventEnvelope(e.eventId(), e.eventType(), 1, e.occurredAt(), e.tenantId(),
            e.correlationId(), TestEvents.MAPPER.createObjectNode());
        EventValidationException ex = assertThrows(EventValidationException.class,
            () -> validator.validate(sinSubject));
        assertTrue(ex.getMessage().contains("subjectId"), ex.getMessage());
    }

    @Test
    void ac06_campoNoDeclaradoSeRechazaPorAdditionalProperties() {
        ObjectNode payload = TestEvents.MAPPER.createObjectNode().put("subjectId", "u").put("nombre", "Juan Perez");
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope conPii = new EventEnvelope(e.eventId(), e.eventType(), 1, e.occurredAt(), e.tenantId(),
            e.correlationId(), payload);
        assertThrows(EventValidationException.class, () -> validator.validate(conPii));
    }

    @Test
    void ac06_tipoSinEsquemaRegistradoSeRechaza() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope desconocido = new EventEnvelope(e.eventId(), "no.existe", 1, Instant.now(), e.tenantId(),
            e.correlationId(), e.payload());
        EventValidationException ex = assertThrows(EventValidationException.class,
            () -> validator.validate(desconocido));
        assertTrue(ex.getMessage().contains("Sin esquema"));
    }

    @Test
    void ac06_eventTypeConCaracteresDePathSeRechaza() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope malicioso = new EventEnvelope(e.eventId(), "../x", 1, Instant.now(), e.tenantId(),
            e.correlationId(), e.payload());
        assertThrows(EventValidationException.class, () -> validator.validate(malicioso));
    }

    @Test
    void ac06_versionDistintaALaDelEsquemaSeRechaza() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope v2 = new EventEnvelope(e.eventId(), e.eventType(), 2, e.occurredAt(), e.tenantId(),
            e.correlationId(), e.payload());
        assertThrows(EventValidationException.class, () -> validator.validate(v2));
    }

    @Test
    void ac08_jsonMalformadoEsErrorFatal() {
        assertThrows(EventValidationException.class, () -> serde.fromJson("{no es json"));
        assertThrows(EventValidationException.class, () -> serde.fromJson("[]"));
    }

    @Test
    void serdeRoundTripConservaSobreYPayload() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        EventEnvelope back = serde.fromJson(serde.toJson(e));
        assertEquals(e.eventId(), back.eventId());
        assertEquals(e.tenantId(), back.tenantId());
        assertEquals(e.occurredAt(), back.occurredAt());
        assertEquals("user-7", back.payload().get("subjectId").asText());
    }
}
