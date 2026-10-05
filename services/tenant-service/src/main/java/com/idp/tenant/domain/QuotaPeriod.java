package com.idp.tenant.domain;

import java.time.LocalDate;
import java.util.UUID;

public record QuotaPeriod(UUID id, UUID tenantId, String metricName, LocalDate periodStart, long limitValue,
                          long consumed, boolean thresholdNotified) {}
