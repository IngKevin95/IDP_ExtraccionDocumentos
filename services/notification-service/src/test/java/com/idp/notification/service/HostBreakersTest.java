package com.idp.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.notification.config.NotificationProperties.Breaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Hallazgo 3: circuit breaker por host que abre ante fallos repetidos. */
class HostBreakersTest {

    private final HostBreakers breakers = new HostBreakers(new Breaker(50f, 4, 4, Duration.ofHours(1), 1));

    @Test
    void abreTrasFallosRepetidosYNoAfectaAOtrosHosts() {
        CircuitBreaker bad = breakers.forHost("Hook.Banco.com");
        for (int i = 0; i < 4; i++) {
            assertThat(bad.tryAcquirePermission()).isTrue();
            bad.onError(1, TimeUnit.MILLISECONDS, new IllegalStateException("5xx"));
        }
        assertThat(bad.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(bad.tryAcquirePermission()).isFalse();
        // Mismo host con otra capitalizacion: mismo breaker.
        assertThat(breakers.forHost("hook.banco.com").tryAcquirePermission()).isFalse();
        // Otro host: cerrado.
        assertThat(breakers.forHost("sano.banco.com").tryAcquirePermission()).isTrue();
    }

    @Test
    void conPocosFallosOMuchosExitosNoAbre() {
        CircuitBreaker cb = breakers.forHost("mixto.banco.com");
        for (int i = 0; i < 4; i++) {
            cb.tryAcquirePermission();
            if (i == 0) {
                cb.onError(1, TimeUnit.MILLISECONDS, new IllegalStateException("5xx"));
            } else {
                cb.onSuccess(1, TimeUnit.MILLISECONDS);
            }
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
