package com.idp.tenant.domain;

import java.util.Map;
import java.util.UUID;

public record TenantConfig(UUID tenantId, String dataKekId, String auditKekId, boolean legalHold,
                           Map<String, Object> settings) {}
