package com.idp.extraction.llm;

import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

/**
 * Acceso resiliente a los proveedores LLM (RF-502, SEC-049 equidad): bulkhead por tenant que acota la
 * concurrencia hacia el LLM, circuit breaker por proveedor y failover transparente al proveedor
 * secundario ante errores (5xx, timeouts) o circuito abierto.
 */
public final class ResilientLlmGateway {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientLlmGateway.class);

    /** PRIMARY: principal con respaldo en el secundario. CASCADE: segunda pasada, prefiere el secundario. */
    public enum Route { PRIMARY, CASCADE }

    private final LlmProvider primary;
    private final LlmProvider secondary;
    private final CircuitBreaker primaryBreaker;
    private final CircuitBreaker secondaryBreaker;
    private final BulkheadRegistry bulkheads;
    private final Duration timeout;

    public ResilientLlmGateway(LlmProvider primary, LlmProvider secondary, Duration timeout, int maxConcurrentPerTenant,
                               Duration maxWait, CircuitBreakerConfig breakerConfig) {
        this.primary = primary;
        this.secondary = secondary;
        this.timeout = timeout;
        this.primaryBreaker = CircuitBreaker.of("llm-primary", breakerConfig);
        this.secondaryBreaker = CircuitBreaker.of("llm-secondary", breakerConfig);
        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(maxConcurrentPerTenant).maxWaitDuration(maxWait).build());
    }

    public static CircuitBreakerConfig defaultBreakerConfig() {
        return CircuitBreakerConfig.custom().failureRateThreshold(50).slidingWindowSize(10)
            .minimumNumberOfCalls(5).waitDurationInOpenState(Duration.ofSeconds(30)).build();
    }

    public CircuitBreaker.State primaryState() {
        return primaryBreaker.getState();
    }

    public CircuitBreaker.State secondaryState() {
        return secondaryBreaker.getState();
    }

    public LlmResponse generate(TenantId tenant, String prompt, List<Resource> images, Route route) {
        Bulkhead bulkhead = bulkheads.bulkhead("tenant-" + tenant.value());
        LlmRequest request = new LlmRequest(tenant, prompt, images, timeout);
        Supplier<LlmResponse> call = Bulkhead.decorateSupplier(bulkhead, () -> dispatch(request, route));
        return call.get();
    }

    private LlmResponse dispatch(LlmRequest request, Route route) {
        boolean cascade = route == Route.CASCADE && secondary != null;
        LlmProvider first = cascade ? secondary : primary;
        CircuitBreaker firstBreaker = cascade ? secondaryBreaker : primaryBreaker;
        Optional<LlmProvider> alt = cascade ? Optional.of(primary) : Optional.ofNullable(secondary);
        CircuitBreaker altBreaker = cascade ? primaryBreaker : secondaryBreaker;
        try {
            return firstBreaker.executeSupplier(() -> first.generate(request));
        } catch (RuntimeException e) {
            if (alt.isEmpty()) {
                throw e;
            }
            LOG.warn("Proveedor LLM {} fallo ({}); se usa el proveedor alterno",
                firstBreaker.getName(), e.getClass().getSimpleName());
            return altBreaker.executeSupplier(() -> alt.get().generate(request));
        }
    }
}
