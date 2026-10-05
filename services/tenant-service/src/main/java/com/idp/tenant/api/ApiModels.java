package com.idp.tenant.api;

import com.idp.tenant.domain.AccessCertification;
import com.idp.tenant.domain.RoleAssignment;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DTOs de la API (ver contracts/openapi/tenant-service.yaml). */
public final class ApiModels {
    private ApiModels() {}

    public record TenantCreateRequest(@NotBlank @Size(max = 255) String name, @NotNull UUID planId) {}

    public record TenantConfigUpdateRequest(@NotNull UUID planId, Map<String, Object> settings) {}

    public record TenantResponse(UUID id, String name, String status, UUID planId) {
        static TenantResponse of(Tenant t) {
            return new TenantResponse(t.id(), t.name(), t.status().name(), t.planId());
        }
    }

    public record LegalHoldRequest(@NotNull Boolean active, String reasonCode) {}

    public record LegalHoldResponse(boolean active, UUID holdId) {}

    public record ConsumptionRequest(@NotBlank String metricName, @NotNull @Positive Long value) {}

    public record BreakGlassRequest(@NotBlank String userId, @NotBlank String approvedBy,
                                    @NotNull @Positive Long ttlMinutes, @NotBlank @Size(max = 500) String justification) {}

    public record CertificationRequest(@NotNull Integer year, @NotNull Integer quarter) {}

    public record CertificationResponse(UUID id, UUID tenantId, int year, int quarter, Instant generatedAt,
                                        String generatedBy, String reportJson, String reportSha256,
                                        String signature, String keyId) {
        static CertificationResponse of(AccessCertification c) {
            return new CertificationResponse(c.id(), c.tenantId(), c.year(), c.quarter(), c.generatedAt(),
                    c.generatedBy(), c.reportJson(), c.reportSha256(), c.signature(), c.keyId());
        }
    }

    public record VerificationResponse(boolean valid) {}

    public record UserAssignRequest(@NotBlank @Size(max = 255) String userId, @NotNull TenantRole role,
                                    Instant expiresAt) {}

    public record RoleAssignmentResponse(UUID id, String userId, String role, String grantedBy, String approvedBy,
                                         Instant expiresAt, Instant createdAt) {
        static RoleAssignmentResponse of(RoleAssignment r) {
            return new RoleAssignmentResponse(r.id(), r.userId(), r.role(), r.grantedBy(), r.approvedBy(),
                    r.expiresAt(), r.createdAt());
        }
    }
}
