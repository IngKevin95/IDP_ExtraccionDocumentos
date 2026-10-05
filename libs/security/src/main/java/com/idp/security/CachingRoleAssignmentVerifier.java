package com.idp.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Revalida tenant y rol contra {@link RoleAssignmentSource} con cache local de TTL maximo 30 s
 * (identidad-autorizacion AC-03). {@link #invalidateUser} la purga al recibir acceso.revocado (AC-04).
 */
public final class CachingRoleAssignmentVerifier implements RoleAssignmentVerifier {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    /** Tamano maximo de la cache; al llenarse Caffeine desaloja por frecuencia/recencia, sin vaciado global. */
    private static final int MAX_ENTRIES = 10_000;

    private record Key(String tenantId, String userId, String role) {
    }

    private final RoleAssignmentSource source;
    private final Clock clock;
    private final Cache<Key, Boolean> cache;

    public CachingRoleAssignmentVerifier(RoleAssignmentSource source) {
        this(source, Clock.systemUTC(), DEFAULT_TTL);
    }

    public CachingRoleAssignmentVerifier(RoleAssignmentSource source, Clock clock, Duration ttl) {
        if (ttl.compareTo(DEFAULT_TTL) > 0 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("El TTL debe estar entre 0 y 30 s");
        }
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(ttl)
            .ticker(() -> TimeUnit.MILLISECONDS.toNanos(this.clock.millis()))
            .build();
    }

    @Override
    public boolean hasRole(String tenantId, String userId, String role) {
        if (tenantId == null || userId == null || role == null) {
            return false;
        }
        return cache.get(new Key(tenantId, userId, role), k -> source.hasRole(tenantId, userId, role));
    }

    /** Purga todas las entradas del usuario en el tenant (evento acceso.revocado). */
    public void invalidateUser(String tenantId, String userId) {
        cache.asMap().keySet().removeIf(k -> k.tenantId().equals(tenantId) && k.userId().equals(userId));
    }

    public int size() {
        cache.cleanUp();
        return (int) cache.estimatedSize();
    }
}
