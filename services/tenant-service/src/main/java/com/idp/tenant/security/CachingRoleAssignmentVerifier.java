package com.idp.tenant.security;

import com.idp.security.RoleAssignmentVerifier;
import com.idp.tenant.infrastructure.persistence.RoleAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Revalida tenant/rol contra role_assignment (base de control) con cache local de maximo 30 s, invalidada
 * al revocar acceso (acceso.revocado). El JWT solo prueba identidad.
 */
@Component
public class CachingRoleAssignmentVerifier implements RoleAssignmentVerifier {
    private record Entry(boolean allowed, Instant expiresAt) {}

    private final RoleAssignmentRepository roles;
    private final Clock clock;
    private final long ttlSeconds;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CachingRoleAssignmentVerifier(RoleAssignmentRepository roles, Clock clock,
                                         @Value("${idp.tenant.revalidation.cache-ttl-seconds:30}") long ttlSeconds) {
        this.roles = roles;
        this.clock = clock;
        this.ttlSeconds = Math.min(ttlSeconds, 30);
    }

    @Override
    public boolean hasRole(String tenantId, String userId, String role) {
        if (tenantId == null || userId == null || role == null) {
            return false;
        }
        UUID tenant;
        try {
            tenant = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String key = tenantId + "|" + userId + "|" + role;
        Instant now = Instant.now(clock);
        Entry e = cache.get(key);
        if (e != null && e.expiresAt().isAfter(now)) {
            return e.allowed();
        }
        boolean allowed = roles.hasActiveRole(tenant, userId, role, now);
        cache.put(key, new Entry(allowed, now.plusSeconds(ttlSeconds)));
        return allowed;
    }

    /** Invalida las entradas del usuario; si hay transaccion, tras el commit para no recachear datos viejos. */
    public void invalidate(UUID tenantId, String userId) {
        Runnable r = () -> cache.keySet().removeIf(k -> k.startsWith(tenantId + "|" + userId + "|"));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
