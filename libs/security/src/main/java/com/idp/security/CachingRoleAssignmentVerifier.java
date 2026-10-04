package com.idp.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Revalida tenant y rol contra {@link RoleAssignmentSource} con cache local de TTL maximo 30 s
 * (identidad-autorizacion AC-03). {@link #invalidateUser} la purga al recibir acceso.revocado (AC-04).
 */
public final class CachingRoleAssignmentVerifier implements RoleAssignmentVerifier {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 10_000;

    private record Key(String tenantId, String userId, String role) {
    }

    private record Entry(boolean granted, long expiresAtMillis) {
    }

    private final RoleAssignmentSource source;
    private final Clock clock;
    private final long ttlMillis;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    public CachingRoleAssignmentVerifier(RoleAssignmentSource source) {
        this(source, Clock.systemUTC(), DEFAULT_TTL);
    }

    public CachingRoleAssignmentVerifier(RoleAssignmentSource source, Clock clock, Duration ttl) {
        if (ttl.compareTo(DEFAULT_TTL) > 0 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("El TTL debe estar entre 0 y 30 s");
        }
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.ttlMillis = ttl.toMillis();
    }

    @Override
    public boolean hasRole(String tenantId, String userId, String role) {
        if (tenantId == null || userId == null || role == null) {
            return false;
        }
        Key key = new Key(tenantId, userId, role);
        long now = clock.millis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.granted();
        }
        boolean granted = source.hasRole(tenantId, userId, role);
        if (cache.size() >= MAX_ENTRIES) {
            cache.values().removeIf(e -> e.expiresAtMillis() <= now);
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(granted, now + ttlMillis));
        return granted;
    }

    /** Purga todas las entradas del usuario en el tenant (evento acceso.revocado). */
    public void invalidateUser(String tenantId, String userId) {
        cache.keySet().removeIf(k -> k.tenantId().equals(tenantId) && k.userId().equals(userId));
    }

    public int size() {
        return cache.size();
    }
}
