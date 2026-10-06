package com.idp.notification.service;

import com.idp.notification.config.NotificationProperties;
import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.FailureReason;
import com.idp.notification.domain.TenantPolicy;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.domain.WebhookSubscription;
import com.idp.notification.event.DomainEvents;
import com.idp.notification.http.WebhookDispatcher;
import com.idp.notification.http.WebhookDispatcher.Attempt;
import com.idp.notification.store.WebhookRepository;
import com.idp.tenant.context.TenantContextHolder;
import com.idp.tenant.context.TenantDirectory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Despacha las entregas vencidas de cada tenant activo: reclama un lote (SKIP LOCKED + arrendamiento), envia fuera
 * de la transaccion y registra el resultado junto con el evento de outbox en una transaccion corta. Reintentos con
 * backoff exponencial segun la politica del tenant (minimo 30 s); agotados los intentos la entrega queda FALLIDO
 * (DLT) y se emite webhook.fallido. Un error definitivo (SSRF, 4xx) no se reintenta.
 *
 * <p>Aislamiento entre tenants y frente a terceros: un coordinador por tenant (en paralelo, acotado) reclama el lote
 * y reparte los envios en un pool acotado; hay un tope de envios simultaneos por tenant y por host receptor, y un
 * circuit breaker por host que, abierto, pospone las entregas sin gastar intentos. Un tenant lento o un receptor
 * caido no retrasan al resto.
 */
@Component
public class DeliveryWorker {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final Duration DEFER_BUSY = Duration.ofSeconds(5);
    private static final String SECRET_UNAVAILABLE = "SECRET_UNAVAILABLE";

    private final WebhookRepository repository;
    private final WebhookDispatcher dispatcher;
    private final WebhookSecrets secrets;
    private final TenantPolicies policies;
    private final DomainEvents events;
    private final TransactionTemplate tx;
    private final TenantDirectory tenants;
    private final NotificationProperties props;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ConcurrencyLimiter limiter;
    private final HostBreakers breakers;
    private final ThreadPoolExecutor senders;
    private final ThreadPoolExecutor coordinators;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public DeliveryWorker(WebhookRepository repository, WebhookDispatcher dispatcher, WebhookSecrets secrets,
                          TenantPolicies policies, DomainEvents events, TransactionTemplate tx,
                          TenantDirectory tenants, NotificationProperties props, MeterRegistry meters, Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.secrets = secrets;
        this.policies = policies;
        this.events = events;
        this.tx = tx;
        this.tenants = tenants;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
        NotificationProperties.Worker w = props.worker();
        this.limiter = new ConcurrencyLimiter(w.maxPerTenant(), w.maxPerHost());
        this.breakers = new HostBreakers(props.breaker());
        this.senders = new ThreadPoolExecutor(w.poolSize(), w.poolSize(), 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(w.poolSize() * 4), namedDaemon("webhook-sender-"));
        this.coordinators = new ThreadPoolExecutor(w.tenantParallelism(), w.tenantParallelism(), 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(10_000), namedDaemon("webhook-tenant-"));
    }

    private static java.util.concurrent.ThreadFactory namedDaemon(String prefix) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        coordinators.shutdownNow();
        senders.shutdown();
    }

    /**
     * Encola el despacho de cada tenant activo en paralelo (un tenant ya en curso se omite) y retorna sin esperar:
     * un tenant lento no retrasa a los demas. Devuelve cuantos tenants se encolaron.
     */
    public int runAll() {
        int queued = 0;
        for (String tenantId : tenants.activeTenants()) {
            if (!inFlight.add(tenantId)) {
                continue;
            }
            try {
                coordinators.execute(() -> {
                    try {
                        runTenant(tenantId);
                    } catch (RuntimeException e) {
                        LOG.warn("Despacho del tenant {} fallo: {}", tenantId, e.getClass().getSimpleName());
                    } finally {
                        inFlight.remove(tenantId);
                    }
                });
                queued++;
            } catch (RejectedExecutionException e) {
                inFlight.remove(tenantId);
            }
        }
        return queued;
    }

    /** Reclama y despacha el lote vencido del tenant; retorna al terminar el lote. Devuelve entregas reclamadas. */
    public int runTenant(String tenantId) {
        UUID tenant = UUID.fromString(tenantId);
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        List<Future<?>> pending = new ArrayList<>();
        int claimed;
        try {
            List<WebhookDelivery> batch = new ArrayList<>(tx.execute(status ->
                repository.claimDue(tenant, clock.instant(), props.worker().lease(), props.worker().batchSize())));
            claimed = batch.size();
            for (WebhookDelivery d : batch) {
                try {
                    Future<?> f = dispatch(tenantId, d);
                    if (f != null) {
                        pending.add(f);
                    }
                } catch (RuntimeException e) {
                    // Sin tocar la entrega: vuelve a estar disponible al vencer el arrendamiento.
                    LOG.error("Entrega {} no pudo procesarse: {}", d.id(), e.getClass().getSimpleName());
                }
            }
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
        for (Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                LOG.error("Envio fallo: {}", e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
            }
        }
        return claimed;
    }

    /** Prepara la entrega y, si hay cupo y el breaker lo permite, la envia al pool; null si no se encolo envio. */
    private Future<?> dispatch(String tenantId, WebhookDelivery d) {
        WebhookSubscription sub = repository.findSubscription(d.tenantId(), d.webhookId()).orElse(null);
        Instant now = clock.instant();
        if (sub == null || !sub.active()) {
            repository.saveAttempt(d.id(), DeliveryStatus.CANCELADO, d.attempts(), now, d.lastAttemptAt(), null,
                d.lastHttpStatus(), "WEBHOOK_INACTIVE", "Suscripcion eliminada");
            return null;
        }
        TenantPolicy policy = policies.effective(d.tenantId());
        List<String> active;
        try {
            active = openSecrets(d, sub, now);
        } catch (RuntimeException e) {
            secretUnavailable(d, policy, e);
            return null;
        }
        String host = hostOf(sub.url());
        CircuitBreaker breaker = breakers.forHost(host);
        ConcurrencyLimiter.Permit permit;
        try {
            permit = limiter.tryAcquire(tenantId, host, props.worker().permitWait());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (permit == null) {
            defer(d, now.plus(DEFER_BUSY), "busy");
            return null;
        }
        if (!breaker.tryAcquirePermission()) {
            permit.release();
            defer(d, now.plus(props.breaker().openDuration()), "circuit_open");
            return null;
        }
        try {
            return senders.submit(() -> send(tenantId, d, sub, policy, active, breaker, permit));
        } catch (RejectedExecutionException e) {
            breaker.releasePermission();
            permit.release();
            defer(d, now.plus(DEFER_BUSY), "saturated");
            return null;
        }
    }

    private List<String> openSecrets(WebhookDelivery d, WebhookSubscription sub, Instant now) {
        List<String> active = new ArrayList<>();
        active.add(secrets.open(d.tenantId(), sub.id(), sub.secretCurrent()));
        if (sub.previousSecretActive(now)) {
            active.add(secrets.open(d.tenantId(), sub.id(), sub.secretPrevious()));
        }
        return active;
    }

    /**
     * El secreto no se pudo descifrar (KMS caido o dato corrupto): cuenta como intento y pasa a FALLIDO con codigo
     * SECRET_UNAVAILABLE para no reaparecer cada ciclo; emite metrica y alerta sin secretos ni detalle del error.
     */
    private void secretUnavailable(WebhookDelivery d, TenantPolicy policy, RuntimeException e) {
        Attempt a = new Attempt(WebhookDispatcher.Kind.PERMANENT, null, SECRET_UNAVAILABLE,
            FailureReason.ENDPOINT_UNREACHABLE, 0);
        tx.executeWithoutResult(status -> record(d, policy, a));
        meters.counter("webhook.secret.unavailable", "tenant", d.tenantId().toString()).increment();
        LOG.error("SECURITY_ALERT webhook con secreto no disponible tenant={} webhook={} causa={}", d.tenantId(),
            d.webhookId(), e.getClass().getSimpleName());
    }

    private void defer(WebhookDelivery d, Instant until, String cause) {
        tx.executeWithoutResult(status -> repository.defer(d.id(), until));
        meters.counter("webhook.delivery.deferred", "tenant", d.tenantId().toString(), "cause", cause).increment();
    }

    private void send(String tenantId, WebhookDelivery d, WebhookSubscription sub, TenantPolicy policy,
                      List<String> active, CircuitBreaker breaker, ConcurrencyLimiter.Permit permit) {
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        boolean breakerNotified = false;
        try {
            long start = System.nanoTime();
            Attempt attempt = dispatcher.dispatch(sub.url(), d, active, policy.allowedHosts());
            notifyBreaker(breaker, attempt, System.nanoTime() - start);
            breakerNotified = true;
            tx.executeWithoutResult(status -> record(d, policy, attempt));
        } catch (RuntimeException e) {
            if (!breakerNotified) {
                breaker.releasePermission();
            }
            // Sin tocar la entrega: vuelve a estar disponible al vencer el arrendamiento.
            LOG.error("Entrega {} no pudo procesarse: {}", d.id(), e.getClass().getSimpleName());
        } finally {
            permit.release();
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    /** Falla del host = 5xx, 408/429, timeout o red; un 4xx definitivo prueba que el host responde. */
    private static void notifyBreaker(CircuitBreaker breaker, Attempt a, long nanos) {
        switch (a.kind()) {
            case SUCCESS -> breaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
            case RETRYABLE -> breaker.onError(nanos, TimeUnit.NANOSECONDS, new HostFailure());
            case PERMANENT -> {
                if (a.ssrf()) {
                    // Bloqueado antes de conectar: no dice nada de la salud del host.
                    breaker.releasePermission();
                } else {
                    breaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
                }
            }
            default -> breaker.releasePermission();
        }
    }

    private static final class HostFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HostFailure() {
            super("host failure", null, false, false);
        }
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "invalid-host" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "invalid-host";
        }
    }

    private void record(WebhookDelivery d, TenantPolicy policy, Attempt a) {
        Instant now = clock.instant();
        int attempts = d.attempts() + 1;
        String tenant = d.tenantId().toString();
        switch (a.kind()) {
            case SUCCESS -> {
                repository.saveAttempt(d.id(), DeliveryStatus.ENTREGADO, attempts, now, now, now, a.httpStatus(), null,
                    null);
                events.entregado(d, attempts, a.latencyMs());
                meters.counter("webhook.delivery.success", "tenant", tenant).increment();
            }
            case RETRYABLE -> {
                if (attempts >= policy.maxAttempts()) {
                    fail(d, a, attempts, now);
                } else {
                    Duration wait = policy.backoffAfter(attempts);
                    if (wait.compareTo(WebhookAdminService.MIN_BACKOFF) < 0) {
                        wait = WebhookAdminService.MIN_BACKOFF;
                    }
                    repository.saveAttempt(d.id(), DeliveryStatus.PENDIENTE, attempts, now.plus(wait), now, null,
                        a.httpStatus(), a.code(), a.code());
                    meters.counter("webhook.delivery.retry", "tenant", tenant, "cause", cause(a)).increment();
                }
            }
            case PERMANENT -> fail(d, a, attempts, now);
            default -> throw new IllegalStateException("Resultado desconocido");
        }
    }

    private void fail(WebhookDelivery d, Attempt a, int attempts, Instant now) {
        repository.saveAttempt(d.id(), DeliveryStatus.FALLIDO, attempts, now, now, null, a.httpStatus(), a.code(),
            a.code());
        events.fallido(d, a.reason(), attempts);
        meters.counter("webhook.delivery.failure", "tenant", d.tenantId().toString(), "cause", cause(a)).increment();
        if (a.ssrf()) {
            meters.counter("webhook.ssrf.blocked", "tenant", d.tenantId().toString()).increment();
            LOG.warn("SECURITY_ALERT webhook bloqueado por politica anti-SSRF tenant={} webhook={} codigo={}",
                d.tenantId(), d.webhookId(), a.code());
        }
    }

    private static String cause(Attempt a) {
        if (SECRET_UNAVAILABLE.equals(a.code())) {
            return "secret_unavailable";
        }
        return switch (a.reason()) {
            case SSRF_BLOCKED -> "ssrf_blocked";
            case CONNECTION_TIMEOUT -> "timeout";
            case ENDPOINT_UNREACHABLE -> "unreachable";
            case HTTP_ERROR_THRESHOLD_EXCEEDED -> "http_error";
        };
    }
}
