package com.idp.tenant.domain;

import java.util.Map;
import java.util.UUID;

public record Plan(UUID id, String name, Map<String, Object> features, Map<String, Long> quotaLimits) {}
