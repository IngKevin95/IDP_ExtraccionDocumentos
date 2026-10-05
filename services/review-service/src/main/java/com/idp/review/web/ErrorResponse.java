package com.idp.review.web;

import java.util.Map;

/** Cuerpo de error del contrato review-service.yaml (code, message, details). */
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
