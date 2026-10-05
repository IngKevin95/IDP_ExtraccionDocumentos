package com.idp.audit.api;

import com.idp.audit.domain.LegalHoldRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** DTOs de la API (ver contracts/openapi/audit-service.yaml). */
public final class ApiModels {
    private ApiModels() {}

    public enum LegalHoldAction { APPLY, RELEASE }

    /** reasonCode es un agregado opcional al contrato: codigo del evento legalhold.aplicado. */
    public record LegalHoldRequest(@NotNull LegalHoldAction action, UUID documentId,
                                   @NotBlank @Size(max = 2000) String reason, String reasonCode) {}

    public record LegalHoldResponse(UUID holdId, UUID tenantId, UUID documentId, String status, Instant appliedAt,
                                    String appliedBy) {
        static LegalHoldResponse of(LegalHoldRecord h) {
            return new LegalHoldResponse(h.id(), h.tenantId(), h.documentId(), h.status().name(), h.createdAt(),
                    h.appliedBy());
        }
    }

    public record PurgeCheckRequest(UUID documentId, @Size(max = 2000) String reason) {}

    public record PurgeCheckResponse(boolean allowed) {}

    public record SignatureVerificationResponse(boolean valid, boolean signatureValid, boolean eventHashesValid) {}

    public record ErrorResponse(String code, String message, Instant timestamp) {
        static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, Instant.now());
        }
    }
}
