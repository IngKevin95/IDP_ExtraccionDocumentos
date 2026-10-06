package com.idp.review.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Campo dudoso de una tarea. {@code boundingBox} es [x, y, ancho, alto] normalizado entre 0 y 1. */
public record ReviewField(UUID id, UUID taskId, String fieldName, Integer page, String boundingBox,
                          String originalValue, BigDecimal confidence, boolean critical, FieldStatus status,
                          OffsetDateTime createdAt) {
}
