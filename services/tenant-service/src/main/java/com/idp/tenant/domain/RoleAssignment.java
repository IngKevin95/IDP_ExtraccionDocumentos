package com.idp.tenant.domain;

import java.time.Instant;
import java.util.UUID;

public record RoleAssignment(UUID id, UUID tenantId, String userId, String role, String grantedBy,
                             String approvedBy, Instant expiresAt, Instant createdAt, Instant deletedAt,
                             String deletedBy, String deleteReason, String justification) {}
