package com.idp.tenant.domain;

import java.time.Instant;
import java.util.UUID;

public record LegalHold(UUID id, UUID tenantId, String reasonCode, String appliedBy, Instant appliedAt,
                        String releasedBy, Instant releasedAt) {}
