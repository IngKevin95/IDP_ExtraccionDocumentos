package com.idp.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Hallazgo 3: concurrencia maxima por tenant y por host. */
class ConcurrencyLimiterTest {

    private static final Duration NO_WAIT = Duration.ZERO;

    @Test
    void elTopePorTenantSeRespetaYSeLiberaAlTerminar() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(2, 10);
        ConcurrencyLimiter.Permit a = limiter.tryAcquire("t1", "h1", NO_WAIT);
        ConcurrencyLimiter.Permit b = limiter.tryAcquire("t1", "h2", NO_WAIT);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(limiter.tryAcquire("t1", "h3", NO_WAIT)).isNull();
        // Otro tenant no se ve afectado por el saturado.
        ConcurrencyLimiter.Permit other = limiter.tryAcquire("t2", "h3", NO_WAIT);
        assertThat(other).isNotNull();
        a.release();
        a.release(); // idempotente: no libera dos cupos
        assertThat(limiter.tryAcquire("t1", "h3", NO_WAIT)).isNotNull();
        assertThat(limiter.tryAcquire("t1", "h4", NO_WAIT)).isNull();
    }

    @Test
    void elTopePorHostSeComparteEntreTenantsYNoFiltraCuposDeTenant() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(5, 1);
        ConcurrencyLimiter.Permit first = limiter.tryAcquire("t1", "hook.banco.com", NO_WAIT);
        assertThat(first).isNotNull();
        assertThat(limiter.tryAcquire("t2", "hook.banco.com", NO_WAIT)).isNull();
        assertThat(limiter.tryAcquire("t2", "otro.banco.com", NO_WAIT)).isNotNull();
        // El intento fallido por host no retuvo el cupo de tenant de t2: sigue teniendo capacidad (4 libres de 5).
        first.release();
        assertThat(limiter.tryAcquire("t2", "hook.banco.com", NO_WAIT)).isNotNull();
    }

    @Test
    void topesInvalidosSeRechazan() {
        assertThatThrownBy(() -> new ConcurrencyLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConcurrencyLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
