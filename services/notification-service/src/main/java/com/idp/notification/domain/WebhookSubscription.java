package com.idp.notification.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Suscripcion de webhook del tenant. Los secretos viajan siempre cifrados (sobre con la KEK del tenant);
 * el secreto anterior solo existe mientras dura la rotacion.
 */
public record WebhookSubscription(
        UUID id,
        UUID tenantId,
        String url,
        Set<WebhookEvent> events,
        boolean active,
        String secretCurrent,
        String secretPrevious,
        Instant secretPreviousExpiresAt,
        String createdBy,
        Instant createdAt,
        Instant rotatedAt) {

    public WebhookSubscription {
        events = events.isEmpty() ? EnumSet.noneOf(WebhookEvent.class) : EnumSet.copyOf(events);
    }

    public boolean subscribedTo(WebhookEvent event) {
        return events.contains(event);
    }

    /** true si hay un secreto anterior todavia vigente en {@code now}. */
    public boolean previousSecretActive(Instant now) {
        return secretPrevious != null && secretPreviousExpiresAt != null && now.isBefore(secretPreviousExpiresAt);
    }
}
