package com.idp.notification.service;

import com.idp.notification.config.NotificationProperties;
import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.TenantPolicy;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.domain.WebhookEvent;
import com.idp.notification.domain.WebhookSubscription;
import com.idp.notification.net.SsrfViolationException;
import com.idp.notification.net.WebhookUrlPolicy;
import com.idp.notification.service.Exceptions.ConflictException;
import com.idp.notification.service.Exceptions.InvalidRequestException;
import com.idp.notification.service.Exceptions.NotFoundException;
import com.idp.notification.store.WebhookRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gestion de suscripciones, secretos, politica y entregas del tenant del llamador (el tenant sale del JWT, nunca
 * del path ni del body). Los secretos en claro solo se devuelven al crear o rotar.
 */
@Service
public class WebhookAdminService {

    static final int MAX_ACTIVE_SUBSCRIPTIONS = 20;
    private static final Logger LOG = LoggerFactory.getLogger(WebhookAdminService.class);

    private final WebhookRepository repository;
    private final WebhookSecrets secrets;
    private final WebhookUrlPolicy urlPolicy;
    private final TenantPolicies policies;
    private final NotificationProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookAdminService(WebhookRepository repository, WebhookSecrets secrets, WebhookUrlPolicy urlPolicy,
                               TenantPolicies policies, NotificationProperties props, TransactionTemplate tx,
                               Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.urlPolicy = urlPolicy;
        this.policies = policies;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Suscripcion recien creada o rotada junto con el secreto en claro (unica vez que se expone). */
    public record WithSecret(WebhookSubscription subscription, String secret) {
    }

    public WithSecret create(Caller caller, String url, List<String> events) {
        UUID tenant = UUID.fromString(caller.tenantId());
        Set<WebhookEvent> parsed = parseEvents(events);
        validateUrl(url, tenant);
        if (repository.countActiveSubscriptions(tenant) >= MAX_ACTIVE_SUBSCRIPTIONS) {
            throw new ConflictException("WEBHOOK_LIMIT_REACHED", "Limite de suscripciones activas alcanzado");
        }
        UUID id = UUID.randomUUID();
        String secret = secrets.generate();
        Instant now = clock.instant();
        WebhookSubscription sub = new WebhookSubscription(id, tenant, url.strip(), parsed, true,
            secrets.seal(tenant, id, secret), null, null, caller.userId(), now, null);
        repository.insertSubscription(sub);
        LOG.info("Webhook {} creado por el tenant {}", id, tenant);
        return new WithSecret(sub, secret);
    }

    public List<WebhookSubscription> list(Caller caller) {
        return repository.activeSubscriptions(UUID.fromString(caller.tenantId()));
    }

    public void delete(Caller caller, UUID id) {
        UUID tenant = UUID.fromString(caller.tenantId());
        if (!repository.deactivate(tenant, id)) {
            throw new NotFoundException("Webhook no encontrado");
        }
        LOG.info("Webhook {} desactivado en el tenant {}", id, tenant);
    }

    /**
     * Inicia una rotacion: el secreto nuevo pasa a vigente y el anterior queda activo hasta
     * {@code now + secret-overlap} o hasta {@link #endRotation}. Con dos secretos activos cada envio lleva dos firmas.
     */
    public WithSecret rotate(Caller caller, UUID id) {
        UUID tenant = UUID.fromString(caller.tenantId());
        String fresh = secrets.generate();
        WebhookSubscription updated = tx.execute(status -> {
            WebhookSubscription current = active(tenant, id);
            Instant now = clock.instant();
            if (current.previousSecretActive(now)) {
                throw new ConflictException("WEBHOOK_ROTATION_IN_PROGRESS", "Ya hay una rotacion en curso");
            }
            Instant expires = now.plus(props.secretOverlap());
            String sealed = secrets.seal(tenant, id, fresh);
            repository.saveRotation(tenant, id, sealed, current.secretCurrent(), expires, now);
            return new WebhookSubscription(id, tenant, current.url(), current.events(), true, sealed,
                current.secretCurrent(), expires, current.createdBy(), current.createdAt(), now);
        });
        LOG.info("Rotacion de secreto iniciada en el webhook {} del tenant {}", id, tenant);
        return new WithSecret(updated, fresh);
    }

    /** Cierra la rotacion revocando el secreto anterior. */
    public void endRotation(Caller caller, UUID id) {
        UUID tenant = UUID.fromString(caller.tenantId());
        WebhookSubscription current = active(tenant, id);
        if (current.secretPrevious() == null) {
            throw new ConflictException("WEBHOOK_NO_ROTATION", "No hay rotacion en curso");
        }
        repository.saveRotation(tenant, id, current.secretCurrent(), null, null, current.rotatedAt());
        LOG.info("Rotacion de secreto finalizada en el webhook {} del tenant {}", id, tenant);
    }

    public TenantPolicy policy(Caller caller) {
        return policies.effective(UUID.fromString(caller.tenantId()));
    }

    public TenantPolicy updatePolicy(Caller caller, List<String> allowedHosts, int maxAttempts,
                                     Duration initialBackoff, double multiplier, Duration maxBackoff) {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new InvalidRequestException("WEBHOOK_POLICY_INVALID", "maxAttempts debe estar entre 1 y 10");
        }
        if (initialBackoff.compareTo(Duration.ofSeconds(1)) < 0 || initialBackoff.compareTo(Duration.ofHours(1)) > 0) {
            throw new InvalidRequestException("WEBHOOK_POLICY_INVALID", "initialBackoff debe estar entre 1s y 1h");
        }
        if (multiplier < 1.0 || multiplier > 10.0) {
            throw new InvalidRequestException("WEBHOOK_POLICY_INVALID", "backoffMultiplier debe estar entre 1 y 10");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofHours(24)) > 0) {
            throw new InvalidRequestException("WEBHOOK_POLICY_INVALID",
                "maxBackoff debe estar entre initialBackoff y 24h");
        }
        List<String> hosts = allowedHosts == null ? List.of() : allowedHosts.stream()
            .map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).distinct().toList();
        if (hosts.size() > 50 || hosts.stream().anyMatch(h -> !h.matches("(\\*\\.)?[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?"))) {
            throw new InvalidRequestException("WEBHOOK_POLICY_INVALID", "allowedHosts invalido");
        }
        TenantPolicy p = new TenantPolicy(hosts, maxAttempts, initialBackoff, multiplier, maxBackoff);
        repository.savePolicy(UUID.fromString(caller.tenantId()), p, clock.instant());
        return p;
    }

    public record DeliveryPage(List<WebhookDelivery> data, int limit, int offset, int total) {
    }

    public DeliveryPage deliveries(Caller caller, UUID webhookId, DeliveryStatus status, int limit, int offset) {
        UUID tenant = UUID.fromString(caller.tenantId());
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new InvalidRequestException("WEBHOOK_INVALID_PAGE", "limit 1..100 y offset >= 0");
        }
        repository.findSubscription(tenant, webhookId).orElseThrow(() -> new NotFoundException("Webhook no encontrado"));
        return new DeliveryPage(repository.listDeliveries(tenant, webhookId, status, limit, offset), limit, offset,
            repository.countDeliveries(tenant, webhookId, status));
    }

    /** Reintento manual de una entrega en DLT (FALLIDO). */
    public WebhookDelivery retry(Caller caller, UUID webhookId, UUID deliveryId) {
        UUID tenant = UUID.fromString(caller.tenantId());
        repository.findSubscription(tenant, webhookId).orElseThrow(() -> new NotFoundException("Webhook no encontrado"));
        WebhookDelivery d = repository.findDelivery(tenant, deliveryId)
            .filter(x -> x.webhookId().equals(webhookId))
            .orElseThrow(() -> new NotFoundException("Entrega no encontrada"));
        if (!repository.requeueFailed(tenant, d.id(), clock.instant())) {
            throw new ConflictException("WEBHOOK_DELIVERY_NOT_FAILED", "Solo se reintentan entregas FALLIDO");
        }
        LOG.info("Reintento manual de la entrega {} del webhook {}", deliveryId, webhookId);
        return repository.findDelivery(tenant, deliveryId).orElseThrow();
    }

    private WebhookSubscription active(UUID tenant, UUID id) {
        return repository.findSubscription(tenant, id).filter(WebhookSubscription::active)
            .orElseThrow(() -> new NotFoundException("Webhook no encontrado"));
    }

    private void validateUrl(String url, UUID tenant) {
        try {
            urlPolicy.validate(url, policies.effective(tenant).allowedHosts());
        } catch (SsrfViolationException e) {
            throw new InvalidRequestException("WEBHOOK_URL_" + e.code(), "URL de webhook no admitida: " + e.code());
        }
    }

    private static Set<WebhookEvent> parseEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            throw new InvalidRequestException("WEBHOOK_INVALID_EVENT", "Se requiere al menos un evento");
        }
        Set<WebhookEvent> out = EnumSet.noneOf(WebhookEvent.class);
        for (String e : events) {
            out.add(WebhookEvent.fromWire(e).orElseThrow(
                () -> new InvalidRequestException("WEBHOOK_INVALID_EVENT", "Evento no soportado")));
        }
        return out;
    }
}
