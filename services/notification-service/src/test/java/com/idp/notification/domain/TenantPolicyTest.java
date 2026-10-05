package com.idp.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AC-06 / AC-16: backoff exponencial configurable con tope. */
class TenantPolicyTest {

    @Test
    void backoffCrecePorElMultiplicadorYRespetaElTope() {
        TenantPolicy p = new TenantPolicy(List.of("a.test"), 5, Duration.ofSeconds(30), 2.0, Duration.ofSeconds(100));
        assertThat(p.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.backoffAfter(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.backoffAfter(3)).isEqualTo(Duration.ofSeconds(100));
        assertThat(p.backoffAfter(50)).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    void multiplicadorUnoDaEsperaConstanteYLaListaDeHostsEsInmutable() {
        TenantPolicy p = new TenantPolicy(new java.util.ArrayList<>(List.of("a.test")), 3, Duration.ofSeconds(5), 1.0,
            Duration.ofMinutes(1));
        assertThat(p.backoffAfter(1)).isEqualTo(p.backoffAfter(4)).isEqualTo(Duration.ofSeconds(5));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.allowedHosts().add("b.test"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
