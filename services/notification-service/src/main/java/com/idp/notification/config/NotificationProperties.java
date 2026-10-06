package com.idp.notification.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Parametros del servicio ({@code idp.notification.*}); la politica por tenant vive en base de datos. */
@ConfigurationProperties("idp.notification")
public record NotificationProperties(
        @DefaultValue("5") int defaultMaxAttempts,
        @DefaultValue("30s") Duration defaultInitialBackoff,
        @DefaultValue("2.0") double defaultBackoffMultiplier,
        @DefaultValue("1h") Duration defaultMaxBackoff,
        @DefaultValue("7d") Duration secretOverlap,
        @DefaultValue("false") boolean allowInsecureHttp,
        @DefaultValue Http http,
        @DefaultValue Worker worker,
        @DefaultValue("dns") HostVerification hostVerification,
        Map<String, List<String>> platformApprovedHosts,
        List<Integer> allowedPorts,
        @DefaultValue("3") int maxManualRetries,
        @DefaultValue Breaker breaker) {

    /** Como se activa un host nuevo de la allowlist del tenant (el TENANT_ADMIN no se autoriza a si mismo). */
    public enum HostVerification {
        /** Propiedad del dominio demostrada con un TXT DNS {@code _idp-verify.<dominio>}. */
        DNS,
        /** Aprobacion explicita de plataforma ({@code platform-approved-hosts}). */
        PLATFORM,
        /** Sin control: solo con idp.security.dev-mode=true. */
        NONE
    }

    public NotificationProperties {
        platformApprovedHosts = platformApprovedHosts == null ? Map.of() : Map.copyOf(platformApprovedHosts);
        allowedPorts = allowedPorts == null ? List.of() : List.copyOf(allowedPorts);
    }

    /** Timeouts estrictos del cliente saliente (mitigan tarpitting). */
    public record Http(
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("10s") Duration totalTimeout) {
    }

    /**
     * Planificacion y limites del despachador: {@code tenantParallelism} coordinadores (uno por tenant a la vez),
     * {@code poolSize} hilos de envio, y topes de concurrencia por tenant y por host receptor.
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("10") int batchSize,
            @DefaultValue("5m") Duration lease,
            @DefaultValue("8") int tenantParallelism,
            @DefaultValue("16") int poolSize,
            @DefaultValue("2") int maxPerTenant,
            @DefaultValue("2") int maxPerHost,
            @DefaultValue("20s") Duration permitWait) {
    }

    /** Circuit breaker por host receptor. */
    public record Breaker(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("10") int slidingWindowSize,
            @DefaultValue("5") int minimumCalls,
            @DefaultValue("60s") Duration openDuration,
            @DefaultValue("2") int halfOpenCalls) {
    }
}
