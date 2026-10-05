package com.idp.review.domain;

import java.math.BigDecimal;

/** Campo marcado como dudoso por el extraction-service (metadatos de evidencia incluidos). */
public record FieldCandidate(String fieldName, Integer page, String boundingBox, String originalValue,
                             BigDecimal confidence) {
}
