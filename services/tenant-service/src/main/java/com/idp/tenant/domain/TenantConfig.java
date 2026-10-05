package com.idp.tenant.domain;

import java.util.Map;
import java.util.UUID;

public record TenantConfig(UUID tenantId, String dataKekId, String auditKekId,
                           Map<String, Object> settings) {}
