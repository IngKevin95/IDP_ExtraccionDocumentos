package com.idp.notification.domain;

/** Motivo de fallo definitivo; los nombres son los del enum de webhook.fallido.v1. */
public enum FailureReason {
    HTTP_ERROR_THRESHOLD_EXCEEDED,
    SSRF_BLOCKED,
    CONNECTION_TIMEOUT,
    ENDPOINT_UNREACHABLE,
    SECRET_UNAVAILABLE
}
