package com.idp.notification.api;

import com.idp.notification.domain.TenantPolicy;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.domain.WebhookEvent;
import com.idp.notification.domain.WebhookSubscription;
import com.idp.notification.service.WebhookAdminService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTOs de la API (contracts/openapi/notification-service.yaml). Los secretos solo viajan en create y rotate. */
public final class WebhookDtos {

    private WebhookDtos() {
    }

    public record CreateRequest(String url, List<String> events) {
    }

    public record CreatedResponse(UUID id, String url, List<String> events, String secret, Instant createdAt) {
        static CreatedResponse of(WebhookAdminService.WithSecret w) {
            WebhookSubscription s = w.subscription();
            return new CreatedResponse(s.id(), s.url(), eventNames(s), w.secret(), s.createdAt());
        }
    }

    public record ListItem(UUID id, String url, List<String> events, Instant createdAt, boolean rotating,
                           Instant previousSecretExpiresAt) {
        static ListItem of(WebhookSubscription s, Instant now, boolean maskPath) {
            boolean rotating = s.previousSecretActive(now);
            return new ListItem(s.id(), maskPath ? maskedUrl(s.url()) : s.url(), eventNames(s), s.createdAt(), rotating,
                rotating ? s.secretPreviousExpiresAt() : null);
        }
    }

    public record RotatedResponse(UUID id, String secret, Instant previousSecretExpiresAt) {
        static RotatedResponse of(WebhookAdminService.WithSecret w) {
            return new RotatedResponse(w.subscription().id(), w.secret(), w.subscription().secretPreviousExpiresAt());
        }
    }

    public record PolicyBody(List<String> allowedHosts, int maxAttempts, long initialBackoffSeconds,
                             double backoffMultiplier, long maxBackoffSeconds) {
        static PolicyBody of(TenantPolicy p) {
            return new PolicyBody(p.allowedHosts(), p.maxAttempts(), p.initialBackoff().toSeconds(),
                p.backoffMultiplier(), p.maxBackoff().toSeconds());
        }
    }

    public record DeliveryItem(UUID id, UUID webhookId, UUID documentId, String eventType, String status, int attempts,
                               Instant nextAttemptAt, Instant lastAttemptAt, Instant deliveredAt,
                               Integer lastHttpStatus, String errorCode, Instant createdAt) {
        static DeliveryItem of(WebhookDelivery d) {
            return new DeliveryItem(d.id(), d.webhookId(), d.documentId(), d.eventType(), d.status().name(),
                d.attempts(), d.nextAttemptAt(), d.lastAttemptAt(), d.deliveredAt(), d.lastHttpStatus(),
                d.errorCode(), d.createdAt());
        }
    }

    public record DeliveryPageResponse(List<DeliveryItem> data, int limit, int offset, int total) {
    }

    public record ErrorBody(String code, String message, Instant timestamp) {
        static ErrorBody of(String code, String message) {
            return new ErrorBody(code, message, Instant.now());
        }
    }

    /**
     * Para roles de solo lectura: host visible y ruta enmascarada (la ruta suele contener identificadores o tokens
     * del receptor). Si la URL no se puede interpretar se oculta por completo.
     */
    static String maskedUrl(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            if (u.getScheme() == null || u.getHost() == null) {
                return "***";
            }
            String port = u.getPort() >= 0 ? ":" + u.getPort() : "";
            return u.getScheme() + "://" + u.getHost() + port + "/***";
        } catch (java.net.URISyntaxException e) {
            return "***";
        }
    }

    private static List<String> eventNames(WebhookSubscription s) {
        return s.events().stream().map(WebhookEvent::wireName).sorted().toList();
    }
}
