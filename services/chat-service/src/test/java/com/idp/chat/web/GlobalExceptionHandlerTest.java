package com.idp.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.chat.service.Exceptions.PromptInjectionException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los errores no filtran detalles internos y respetan la forma del contrato (code, message, incidentId). */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void errorInesperadoNoDevuelveElMensajeInterno() {
        var r = handler.unexpected(new IllegalStateException("password=hunter2 en jdbc://interno"));

        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody().code()).isEqualTo("CHAT_INTERNAL_ERROR");
        assertThat(r.getBody().message()).isEqualTo("Error interno.").doesNotContain("hunter2");
        assertThat(r.getBody().incidentId()).isNotNull();
    }

    @Test
    void inyeccionUsaElIncidentIdDelEvento() {
        UUID incident = UUID.randomUUID();

        var r = handler.injection(new PromptInjectionException(incident));

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().code()).isEqualTo("SEC-033-PROMPT-INJECTION");
        assertThat(r.getBody().incidentId()).isEqualTo(incident);
    }
}
