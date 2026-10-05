package com.idp.document.web;

import java.time.Instant;

public record ErrorResponse(String errorCode, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
