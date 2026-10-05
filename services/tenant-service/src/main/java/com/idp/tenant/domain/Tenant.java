package com.idp.tenant.domain;

import java.time.Instant;
import java.util.UUID;

public record Tenant(UUID id, String name, TenantStatus status, UUID planId,
                     Instant deletionDueAt, Instant createdAt, Instant updatedAt) {}
