package com.idp.review.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Correction(UUID id, UUID taskId, String fieldName, String originalValue, String correctedValue,
                         boolean critical, OffsetDateTime createdAt, String createdBy) {
}
