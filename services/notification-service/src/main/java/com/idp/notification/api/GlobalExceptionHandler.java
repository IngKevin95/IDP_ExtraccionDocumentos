package com.idp.notification.api;

import com.idp.notification.api.WebhookDtos.ErrorBody;
import com.idp.notification.service.Exceptions.ConflictException;
import com.idp.notification.service.Exceptions.InvalidRequestException;
import com.idp.notification.service.Exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errores genericos que no revelan existencia de recursos de otros tenants. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorBody> invalid(InvalidRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e.errorCode(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorBody> malformed(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID_REQUEST", "Solicitud invalida");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Recurso no encontrado");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "WEBHOOK_FORBIDDEN", "Acceso denegado");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorBody> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            return body(HttpStatus.valueOf(framework.getStatusCode().value()), "WEBHOOK_HTTP_ERROR",
                "Solicitud no valida");
        }
        LOG.error("Error no controlado: {}", e.getClass().getSimpleName(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_INTERNAL_ERROR", "Error interno");
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorBody.of(code, message));
    }
}
