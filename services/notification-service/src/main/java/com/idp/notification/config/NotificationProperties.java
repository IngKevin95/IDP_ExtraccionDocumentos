package com.idp.notification.config;

import java.time.Duration;
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
        @DefaultValue Worker worker) {

    /** Timeouts estrictos del cliente saliente (mitigan tarpitting). */
    public record Http(
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("10s") Duration totalTimeout) {
    }

    /** Planificacion del despachador de entregas. */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("10") int batchSize,
            @DefaultValue("5m") Duration lease) {
    }
}
