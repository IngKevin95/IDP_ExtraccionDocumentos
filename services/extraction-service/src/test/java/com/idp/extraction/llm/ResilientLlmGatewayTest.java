package com.idp.extraction.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.llm.LlmProvider;
import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Bulkhead por tenant y failover con circuit breaker (RF-502, equidad entre tenants). */
class ResilientLlmGatewayTest {

    private static final LlmResponse OK = new LlmResponse("{}", "m", "STOP", new LlmResponse.Usage(1, 1));
    private static final TenantId A = new TenantId("tenant-a");
    private static final TenantId B = new TenantId("tenant-b");

    @Test
    void bulkheadLimitaLaConcurrenciaPorTenantSinAfectarAOtros() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LlmProvider slow = req -> {
            if (req.tenantId().equals(A)) {
                inCall.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return OK;
        };
        ResilientLlmGateway gw = new ResilientLlmGateway(slow, null, Duration.ofSeconds(5), 1, Duration.ZERO,
            ResilientLlmGateway.defaultBreakerConfig());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LlmResponse> first = pool.submit(() -> gw.generate(A, "p", List.of(), ResilientLlmGateway.Route.PRIMARY));
            assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> gw.generate(A, "p", List.of(), ResilientLlmGateway.Route.PRIMARY))
                .isInstanceOf(BulkheadFullException.class);
            assertThat(gw.generate(B, "p", List.of(), ResilientLlmGateway.Route.PRIMARY)).isEqualTo(OK);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(OK);
            assertThat(gw.generate(A, "p", List.of(), ResilientLlmGateway.Route.PRIMARY)).isEqualTo(OK);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void sinSecundarioElErrorDelPrincipalSePropaga() {
        LlmProvider failing = req -> {
            throw new IllegalStateException("HTTP 503");
        };
        ResilientLlmGateway gw = new ResilientLlmGateway(failing, null, Duration.ofSeconds(5), 2, Duration.ZERO,
            ResilientLlmGateway.defaultBreakerConfig());

        assertThatThrownBy(() -> gw.generate(A, "p", List.of(), ResilientLlmGateway.Route.PRIMARY))
            .hasMessageContaining("503");
    }

    @Test
    void laCascadaPrefiereElSecundarioYCaeAlPrincipalSiFalla() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger secondaryCalls = new AtomicInteger();
        LlmProvider primary = req -> {
            primaryCalls.incrementAndGet();
            return OK;
        };
        LlmProvider secondary = req -> {
            secondaryCalls.incrementAndGet();
            throw new IllegalStateException("HTTP 500");
        };
        ResilientLlmGateway gw = new ResilientLlmGateway(primary, secondary, Duration.ofSeconds(5), 2, Duration.ZERO,
            ResilientLlmGateway.defaultBreakerConfig());

        assertThat(gw.generate(A, "p", List.of(), ResilientLlmGateway.Route.CASCADE)).isEqualTo(OK);

        assertThat(secondaryCalls.get()).isEqualTo(1);
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(gw.primaryState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(gw.secondaryState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
