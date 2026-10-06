package com.idp.chat.infra;

import com.idp.chat.config.ChatProperties;
import com.idp.chat.service.Exceptions.CapacityExceededException;
import com.idp.chat.service.Exceptions.LlmUnavailableException;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Llamada acotada al LLM (SEC-049, ADR 0023): timeout real por peticion (la llamada corre en un hilo virtual y se
 * cancela al vencer {@link LlmRequest#timeout()}), bulkhead por tenant (tope de concurrencia; saturado se rechaza con
 * {@link CapacityExceededException} y el permiso solo se libera cuando la llamada subyacente termina de verdad, de
 * modo que una llamada colgada sigue contando) y failover al modelo secundario si hay uno configurado.
 */
public final class ResilientLlm implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientLlm.class);

    private final LlmProvider primary;
    private final LlmProvider secondary;
    private final BulkheadRegistry bulkheads;
    private final Duration retryAfter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientLlm(LlmProvider primary, LlmProvider secondary, ChatProperties.Llm cfg) {
        this.primary = primary;
        this.secondary = secondary;
        this.retryAfter = cfg.retryAfter();
        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(cfg.bulkheadMaxConcurrent())
                .maxWaitDuration(cfg.bulkheadMaxWait()).build());
    }

    public LlmResponse generate(LlmRequest request) {
        Bulkhead bulkhead = bulkheads.bulkhead("tenant-" + request.tenantId().value());
        try {
            return call(primary, request, bulkhead);
        } catch (CapacityExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            if (secondary == null) {
                throw e;
            }
            LOG.warn("LLM principal fallo ({}); se usa el modelo secundario", e.getClass().getSimpleName());
            return call(secondary, request, bulkhead);
        }
    }

    private LlmResponse call(LlmProvider provider, LlmRequest request, Bulkhead bulkhead) {
        try {
            bulkhead.acquirePermission();
        } catch (BulkheadFullException e) {
            throw new CapacityExceededException(Math.max(1, retryAfter.toSeconds()));
        }
        Future<LlmResponse> future;
        try {
            future = executor.submit(() -> {
                try {
                    return provider.generate(request);
                } finally {
                    bulkhead.onComplete();
                }
            });
        } catch (RuntimeException e) {
            bulkhead.onComplete();
            throw new LlmUnavailableException("No se pudo iniciar la llamada al LLM", e);
        }
        try {
            return future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmUnavailableException("El proveedor LLM excedio el tiempo limite", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Llamada al LLM interrumpida", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LlmUnavailableException unavailable) {
                throw unavailable;
            }
            throw new LlmUnavailableException("Proveedor LLM no disponible", cause);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
