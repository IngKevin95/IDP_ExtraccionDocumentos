package com.idp.renderer.api;

import com.idp.renderer.core.RenderException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RenderException.class)
    ResponseEntity<ErrorResponse> render(RenderException e) {
        return respond(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return respond(413, "ERR_LIMIT_EXCEEDED", "El archivo excede el tamano maximo.");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return respond(400, "ERR_VALIDATION", "Solicitud multipart invalida: se requiere la parte 'file'.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        return respond(500, "ERR_INTERNAL", "Error interno del servicio de renderizacion.");
    }

    private static ResponseEntity<ErrorResponse> respond(int status, String code, String message) {
        String incident = UUID.randomUUID().toString();
        if (status >= 500) {
            LOG.error("incident={} code={}", incident, code);
        } else {
            LOG.warn("incident={} code={}", incident, code);
        }
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, incident));
    }
}
