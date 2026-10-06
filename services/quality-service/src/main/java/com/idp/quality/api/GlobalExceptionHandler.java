package com.idp.quality.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errores genericos que no revelan detalles internos ni existencia de recursos de otros tenants. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Recurso inexistente en el tenant del llamador. */
    public static class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "QUALITY_FORBIDDEN", "Acceso denegado");
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
        com.fasterxml.jackson.core.JsonProcessingException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "QUALITY_INVALID_REQUEST", "Solicitud invalida");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "QUALITY_NOT_FOUND", "Recurso no encontrado");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
