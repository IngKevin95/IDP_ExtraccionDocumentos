package com.idp.notification.service;

import com.idp.notification.config.NotificationProperties;
import com.idp.notification.domain.TenantPolicy;
import com.idp.notification.store.WebhookRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Politica efectiva del tenant: la guardada en su silo o, si no hay, los valores por defecto sin hosts permitidos. */
@Component
public class TenantPolicies {

    private final WebhookRepository repository;
    private final NotificationProperties props;

    public TenantPolicies(WebhookRepository repository, NotificationProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public TenantPolicy effective(UUID tenantId) {
        return repository.findPolicy(tenantId).orElseGet(() -> new TenantPolicy(List.of(), props.defaultMaxAttempts(),
            props.defaultInitialBackoff(), props.defaultBackoffMultiplier(), props.defaultMaxBackoff()));
    }
}
