package com.idp.tenant.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessCertification(UUID id, UUID tenantId, int year, int quarter, String generatedBy,
                                  Instant generatedAt, String reportJson, String reportSha256,
                                  String signature, String keyId) {}
