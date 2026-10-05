package com.idp.notification.domain;

import java.time.Duration;
import java.util.List;

/**
 * Politica del tenant: hosts receptores permitidos (allowlist, ADR 0017) y reintentos con backoff exponencial
 * configurable. Sin hosts permitidos no se admite ningun destino.
 */
public record TenantPolicy(
        List<String> allowedHosts,
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        Duration maxBackoff) {

    public TenantPolicy {
        allowedHosts = List.copyOf(allowedHosts);
    }

    /** Espera antes del siguiente intento tras {@code attemptsMade} intentos fallidos (>= 1), con tope. */
    public Duration backoffAfter(int attemptsMade) {
        double millis = initialBackoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, attemptsMade - 1));
        long capped = (long) Math.min(millis, (double) maxBackoff.toMillis());
        return Duration.ofMillis(Math.max(0, capped));
    }
}
