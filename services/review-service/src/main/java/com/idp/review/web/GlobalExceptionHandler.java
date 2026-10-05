package com.idp.review.web;

import com.idp.review.service.Exceptions.ConflictException;
import com.idp.review.service.Exceptions.FourEyesViolationException;
import com.idp.review.service.Exceptions.InvalidRequestException;
import com.idp.review.service.Exceptions.InvalidStateException;
import com.idp.review.service.Exceptions.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errores genericos que no revelan existencia de recursos de otro tenant (SEC-003). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(TaskNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Tarea no encontrada");
    }

    @ExceptionHandler(FourEyesViolationException.class)
    ResponseEntity<ErrorResponse> fourEyes(FourEyesViolationException e) {
        return body(HttpStatus.FORBIDDEN, "REVIEW_FOUR_EYES_VIOLATION", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "REVIEW_FORBIDDEN", "Acceso denegado");
    }

    @ExceptionHandler(InvalidStateException.class)
    ResponseEntity<ErrorResponse> invalidState(InvalidStateException e) {
        return body(HttpStatus.BAD_REQUEST, "REVIEW_INVALID_STATE", e.getMessage());
    }

    @ExceptionHandler({InvalidRequestException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalid(Exception e) {
        String msg = e instanceof InvalidRequestException ? e.getMessage() : "Solicitud invalida";
        return body(HttpStatus.BAD_REQUEST, "REVIEW_INVALID_REQUEST", msg);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            return body(HttpStatus.valueOf(framework.getStatusCode().value()), "REVIEW_HTTP_ERROR",
                    "Solicitud no valida");
        }
        LOG.error("Error no controlado: {}", e.getClass().getSimpleName(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "REVIEW_INTERNAL_ERROR", "Error interno");
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
