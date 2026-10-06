package com.idp.chat.web;

import com.idp.chat.service.Exceptions.AccessDeniedException;
import com.idp.chat.service.Exceptions.CapacityExceededException;
import com.idp.chat.service.Exceptions.ContentUnavailableException;
import com.idp.chat.service.Exceptions.DocumentNotFoundException;
import com.idp.chat.service.Exceptions.InvalidRequestException;
import com.idp.chat.service.Exceptions.LlmUnavailableException;
import com.idp.chat.service.Exceptions.PromptInjectionException;
import com.idp.chat.service.Exceptions.RateLimitedException;
import com.idp.chat.service.Exceptions.SessionNotFoundException;
import com.idp.chat.service.Exceptions.UnauthenticatedException;
import com.idp.chat.web.ChatDtos.ErrorResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Taxonomia de errores del contrato (code, message, incidentId). Los mensajes son fijos: nunca se devuelve el mensaje
 * de una excepcion interna ni se revela si un recurso existe en otro tenant (SEC-003).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PromptInjectionException.class)
    ResponseEntity<ErrorResponse> injection(PromptInjectionException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("SEC-033-PROMPT-INJECTION",
                "La consulta ha sido bloqueada por políticas de seguridad.", e.incidentId()));
    }

    @ExceptionHandler({AccessDeniedException.class, org.springframework.security.access.AccessDeniedException.class})
    ResponseEntity<ErrorResponse> denied(Exception e) {
        return body(HttpStatus.FORBIDDEN, "CHAT_FORBIDDEN", "Acceso denegado.");
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ErrorResponse> unauthenticated(UnauthenticatedException e) {
        return body(HttpStatus.UNAUTHORIZED, "CHAT_UNAUTHENTICATED", "Credenciales requeridas.");
    }

    @ExceptionHandler({SessionNotFoundException.class, DocumentNotFoundException.class})
    ResponseEntity<ErrorResponse> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "Recurso no encontrado.");
    }

    @ExceptionHandler({InvalidRequestException.class, MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalid(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "CHAT_INVALID_REQUEST", "Solicitud inválida.");
    }

    @ExceptionHandler(LlmUnavailableException.class)
    ResponseEntity<ErrorResponse> llm(LlmUnavailableException e) {
        LOG.error("Proveedor de IA no disponible: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_AI_UNAVAILABLE",
                "El servicio de IA no está disponible, reintente.");
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ErrorResponse> rateLimited(RateLimitedException e) {
        boolean quota = RateLimitedException.QUOTA.equals(e.code());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(ErrorResponse.of(e.code(), quota ? "Cuota diaria de consultas agotada, reintente mas tarde."
                        : "Demasiadas solicitudes, reintente."));
    }

    @ExceptionHandler(CapacityExceededException.class)
    ResponseEntity<ErrorResponse> capacity(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(ErrorResponse.of("CHAT_AI_BUSY", "El servicio de IA esta saturado, reintente."));
    }

    @ExceptionHandler(ContentUnavailableException.class)
    ResponseEntity<ErrorResponse> contentUnavailable(ContentUnavailableException e) {
        LOG.error("Contenido cifrado ilegible: {}", e.getCause() == null ? "ausente"
                : e.getCause().getClass().getSimpleName());
        return body(HttpStatus.GONE, "CHAT_CONTENT_UNAVAILABLE", "El contenido ya no esta disponible.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            return body(HttpStatus.valueOf(framework.getStatusCode().value()), "CHAT_HTTP_ERROR",
                    "Solicitud no válida.");
        }
        UUID incident = UUID.randomUUID();
        LOG.error("Error no controlado incidente={}: {}", incident, e.getClass().getSimpleName(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("CHAT_INTERNAL_ERROR", "Error interno.", incident));
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
