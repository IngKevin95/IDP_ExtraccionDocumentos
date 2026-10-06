package com.idp.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.chat.service.Exceptions;
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

    @Test
    void limiteDeTasaYCuotaDan429ConRetryAfter() {
        var rate = handler.rateLimited(new Exceptions.RateLimitedException(Exceptions.RateLimitedException.RATE, 42));
        assertThat(rate.getStatusCode().value()).isEqualTo(429);
        assertThat(rate.getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(rate.getBody().code()).isEqualTo("CHAT_RATE_LIMITED");

        var quota = handler.rateLimited(new Exceptions.RateLimitedException(Exceptions.RateLimitedException.QUOTA, 0));
        assertThat(quota.getBody().code()).isEqualTo("CHAT_QUOTA_EXCEEDED");
        assertThat(quota.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void saturacionDelBulkheadDa503ConRetryAfter() {
        var r = handler.capacity(new Exceptions.CapacityExceededException(7));

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(r.getBody().code()).isEqualTo("CHAT_AI_BUSY");
    }

    @Test
    void contenidoIlegibleDa410SinDetalles() {
        var r = handler.contentUnavailable(new Exceptions.ContentUnavailableException(
                new IllegalStateException("kek=secreto")));

        assertThat(r.getStatusCode().value()).isEqualTo(410);
        assertThat(r.getBody().code()).isEqualTo("CHAT_CONTENT_UNAVAILABLE");
        assertThat(r.getBody().message()).doesNotContain("secreto");
    }
}
