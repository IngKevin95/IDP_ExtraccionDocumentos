package com.idp.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.chat.config.ChatProperties;
import com.idp.chat.service.Exceptions.CapacityExceededException;
import com.idp.chat.service.Exceptions.LlmUnavailableException;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Timeout real, bulkhead por tenant y failover al modelo secundario (SEC-049, ADR 0023). */
class ResilientLlmTest {

    private static ChatProperties.Llm cfg(int concurrent) {
        return new ChatProperties.Llm("m", "e", "", concurrent, Duration.ZERO, Duration.ofSeconds(3), 10);
    }

    private static LlmRequest request(TenantId tenant, Duration timeout) {
        return new LlmRequest(tenant, "p", List.of(), timeout);
    }

    private static LlmResponse ok(String model) {
        return new LlmResponse("ok", model, "stop", new LlmResponse.Usage(1, 1));
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID().toString());
    }

    @Test
    void elTimeoutCancelaLaLlamadaYLiberaElPermiso() {
        AtomicBoolean interrupted = new AtomicBoolean();
        LlmProvider slow = r -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return ok("slow");
        };
        try (ResilientLlm llm = new ResilientLlm(slow, null, cfg(1))) {
            TenantId t = tenant();
            assertThatThrownBy(() -> llm.generate(request(t, Duration.ofMillis(100))))
                    .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("tiempo");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilTrue(interrupted);
            // Permiso liberado al terminar la llamada cancelada: el tenant vuelve a poder llamar (no es Capacity).
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThatThrownBy(() -> llm.generate(request(t, Duration.ofMillis(50))))
                            .isInstanceOf(LlmUnavailableException.class));
        }
    }

    @Test
    void bulkheadPorTenantRechazaLaSaturacionSinAfectarAOtrosTenants() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LlmProvider blocking = r -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("m");
        };
        try (ResilientLlm llm = new ResilientLlm(blocking, null, cfg(1))) {
            TenantId a = tenant();
            Thread first = Thread.ofVirtual().start(() -> llm.generate(request(a, Duration.ofSeconds(10))));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> llm.generate(request(a, Duration.ofSeconds(1))))
                    .isInstanceOfSatisfying(CapacityExceededException.class,
                            e -> assertThat(e.retryAfterSeconds()).isEqualTo(3));
            // Otro tenant tiene su propio bulkhead: llega al proveedor (y vence por timeout), no se rechaza por capacidad.
            assertThatThrownBy(() -> llm.generate(request(tenant(), Duration.ofMillis(100))))
                    .isInstanceOf(LlmUnavailableException.class);
            release.countDown();
            first.join(5_000);
        }
    }

    @Test
    void failoverAlModeloSecundarioSiElPrincipalFallaOVenceElTiempo() {
        LlmProvider broken = r -> {
            throw new IllegalStateException("5xx");
        };
        LlmProvider backup = r -> ok("secundario");
        try (ResilientLlm llm = new ResilientLlm(broken, backup, cfg(2))) {
            assertThat(llm.generate(request(tenant(), Duration.ofSeconds(2))).modelVersion()).isEqualTo("secundario");
        }
        LlmProvider slow = r -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("lento");
        };
        try (ResilientLlm llm = new ResilientLlm(slow, backup, cfg(2))) {
            assertThat(llm.generate(request(tenant(), Duration.ofMillis(100))).modelVersion())
                    .isEqualTo("secundario");
        }
    }

    @Test
    void sinSecundarioElErrorDelPrincipalSePropagaComoNoDisponible() {
        LlmProvider broken = r -> {
            throw new IllegalStateException("5xx");
        };
        try (ResilientLlm llm = new ResilientLlm(broken, null, cfg(2))) {
            assertThatThrownBy(() -> llm.generate(request(tenant(), Duration.ofSeconds(1))))
                    .isInstanceOf(LlmUnavailableException.class);
        }
    }
}
