package com.idp.document.web;

import com.idp.document.domain.InvalidTransitionException;
import com.idp.document.service.Exceptions.ConflictException;
import com.idp.document.service.Exceptions.DocumentNotFoundException;
import com.idp.document.service.Exceptions.FileTooLargeException;
import com.idp.document.service.Exceptions.InvalidFileFormatException;
import com.idp.document.service.Exceptions.InvalidMetadataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Errores genericos que no revelan existencia de recursos (SEC-003). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidFileFormatException.class)
    ResponseEntity<ErrorResponse> format(InvalidFileFormatException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DOC_INVALID_FORMAT", e.getMessage());
    }

    @ExceptionHandler({FileTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ErrorResponse> size(Exception e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "DOC_FILE_TOO_LARGE", "El archivo excede el limite de tamano");
    }

    @ExceptionHandler({InvalidMetadataException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> metadata(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "DOC_INVALID_METADATA", "Solicitud invalida");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DOC_INVALID_FORMAT", "Tipo de contenido no soportado");
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(DocumentNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "DOC_NOT_FOUND", "Documento no encontrado");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "DOC_FORBIDDEN", "Acceso denegado");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ResponseEntity<ErrorResponse> transition(InvalidTransitionException e) {
        return body(HttpStatus.CONFLICT, "DOC_INVALID_STATE", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            return body(HttpStatus.valueOf(framework.getStatusCode().value()), "DOC_HTTP_ERROR", "Solicitud no valida");
        }
        LOG.error("Error no controlado: {}", e.getClass().getSimpleName(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "DOC_INTERNAL_ERROR", "Error interno");
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
