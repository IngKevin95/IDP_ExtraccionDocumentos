package com.idp.audit.api;

import com.idp.audit.api.ApiModels.ErrorResponse;
import com.idp.audit.domain.Exceptions.BadRequestException;
import com.idp.audit.domain.Exceptions.ChainIntegrityException;
import com.idp.audit.domain.Exceptions.LegalHoldActiveException;
import com.idp.audit.domain.Exceptions.NotFoundException;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.storage.ObjectStore.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errores con el cuerpo ErrorResponse del contrato (code, message, timestamp); sin detalles internos. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "AUDIT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        String msg = e instanceof BadRequestException ? e.getMessage() : "Solicitud invalida";
        return error(HttpStatus.BAD_REQUEST, "AUDIT_BAD_REQUEST", msg);
    }

    @ExceptionHandler(LegalHoldActiveException.class)
    ResponseEntity<ErrorResponse> legalHold(LegalHoldActiveException e) {
        return error(HttpStatus.CONFLICT, "LEGAL_HOLD_ACTIVE", e.getMessage());
    }

    @ExceptionHandler(ChainIntegrityException.class)
    ResponseEntity<ErrorResponse> integrity(ChainIntegrityException e) {
        return error(HttpStatus.CONFLICT, "AUDIT_CHAIN_CORRUPT",
                "Se detecto una inconsistencia en la cadena de auditoria (secuencia " + e.sequenceId() + ").");
    }

    @ExceptionHandler({KeyServiceUnavailableException.class, StorageException.class,
            UnsupportedOperationException.class})
    ResponseEntity<ErrorResponse> dependency(RuntimeException e) {
        LOG.error("Dependencia de auditoria no disponible: {}", e.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_DEPENDENCY_UNAVAILABLE",
                "Servicio de firma o almacenamiento inmutable no disponible.");
    }
}
