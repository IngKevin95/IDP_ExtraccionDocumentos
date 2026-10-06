package com.idp.notification.service;

import com.idp.notification.config.NotificationProperties.Breaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Locale;

/**
 * Circuit breaker por host receptor (compartido entre tenants: la salud del tercero no depende de quien le escribe).
 * Abre ante fallos repetidos (5xx, timeouts, red) y deja de enviarle hasta pasada la ventana de apertura; luego
 * admite unas pocas llamadas de prueba (half-open).
 */
public final class HostBreakers {

    private final CircuitBreakerRegistry registry;

    public HostBreakers(Breaker cfg) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .failureRateThreshold(cfg.failureRateThreshold())
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(cfg.slidingWindowSize())
            .minimumNumberOfCalls(cfg.minimumCalls())
            .waitDurationInOpenState(cfg.openDuration())
            .permittedNumberOfCallsInHalfOpenState(cfg.halfOpenCalls())
            .build());
    }

    public CircuitBreaker forHost(String host) {
        return registry.circuitBreaker(host.toLowerCase(Locale.ROOT));
    }
}
