package com.idp.notification.service;

import com.idp.notification.config.NotificationProperties;
import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.TenantPolicy;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.domain.WebhookSubscription;
import com.idp.notification.event.DomainEvents;
import com.idp.notification.http.WebhookDispatcher;
import com.idp.notification.http.WebhookDispatcher.Attempt;
import com.idp.notification.store.WebhookRepository;
import com.idp.tenant.context.TenantContextHolder;
import com.idp.tenant.context.TenantDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Despacha las entregas vencidas de cada tenant activo: reclama un lote (SKIP LOCKED + arrendamiento), envia fuera
 * de la transaccion y registra el resultado junto con el evento de outbox en una transaccion corta. Reintentos con
 * backoff exponencial segun la politica del tenant; agotados los intentos la entrega queda FALLIDO (DLT) y se
 * emite webhook.fallido. Un error definitivo (SSRF, 4xx) no se reintenta.
 */
@Component
public class DeliveryWorker {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorker.class);

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
    }

    /** Recorre los tenants activos; el fallo de uno no bloquea a los demas. Devuelve entregas procesadas. */
    public int runAll() {
        int total = 0;
        for (String tenantId : tenants.activeTenants()) {
            try {
                total += runTenant(tenantId);
            } catch (RuntimeException e) {
                LOG.warn("Despacho del tenant {} fallo: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        return total;
    }

    public int runTenant(String tenantId) {
        UUID tenant = UUID.fromString(tenantId);
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            List<WebhookDelivery> batch = new ArrayList<>(tx.execute(status ->
                repository.claimDue(tenant, clock.instant(), props.worker().lease(), props.worker().batchSize())));
            for (WebhookDelivery d : batch) {
                try {
                    process(d);
                } catch (RuntimeException e) {
                    // Sin tocar la entrega: vuelve a estar disponible al vencer el arrendamiento.
                    LOG.error("Entrega {} no pudo procesarse: {}", d.id(), e.getClass().getSimpleName());
                }
            }
            return batch.size();
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    private void process(WebhookDelivery d) {
        WebhookSubscription sub = repository.findSubscription(d.tenantId(), d.webhookId()).orElse(null);
        Instant now = clock.instant();
        if (sub == null || !sub.active()) {
            repository.saveAttempt(d.id(), DeliveryStatus.CANCELADO, d.attempts(), now, d.lastAttemptAt(), null,
                d.lastHttpStatus(), "WEBHOOK_INACTIVE", "Suscripcion eliminada");
            return;
        }
        TenantPolicy policy = policies.effective(d.tenantId());
        List<String> active = new ArrayList<>();
        active.add(secrets.open(d.tenantId(), sub.id(), sub.secretCurrent()));
        if (sub.previousSecretActive(now)) {
            active.add(secrets.open(d.tenantId(), sub.id(), sub.secretPrevious()));
        }
        Attempt attempt = dispatcher.dispatch(sub.url(), d, active, policy.allowedHosts());
        tx.executeWithoutResult(status -> record(d, policy, attempt));
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
                    repository.saveAttempt(d.id(), DeliveryStatus.PENDIENTE, attempts,
                        now.plus(policy.backoffAfter(attempts)), now, null, a.httpStatus(), a.code(), a.code());
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
        return switch (a.reason()) {
            case SSRF_BLOCKED -> "ssrf_blocked";
            case CONNECTION_TIMEOUT -> "timeout";
            case ENDPOINT_UNREACHABLE -> "unreachable";
            case HTTP_ERROR_THRESHOLD_EXCEEDED -> "http_error";
        };
    }
}
