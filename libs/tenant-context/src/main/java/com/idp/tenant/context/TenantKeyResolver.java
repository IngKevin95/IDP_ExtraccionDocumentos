package com.idp.tenant.context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class TenantKeyResolver {
    private static final Logger LOG = LoggerFactory.getLogger(TenantKeyResolver.class);
    private static final String SQL = "select data_kek_id, audit_kek_id from tenant_config where tenant_id = ?";

    public record TenantKeys(String dataKekId, String auditKekId) {}

    private final JdbcTemplate controlDb;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, CachedKeys> cache = new ConcurrentHashMap<>();

    private record CachedKeys(TenantKeys keys, Instant loadedAt) {}

    public TenantKeyResolver(JdbcTemplate controlDb, Duration ttl, Clock clock) {
        this.controlDb = controlDb;
        this.ttl = ttl;
        this.clock = clock;
    }

    public TenantKeys resolve(String tenantId) {
        Instant now = clock.instant();
        CachedKeys cached = cache.get(tenantId);
        if (cached != null && Duration.between(cached.loadedAt(), now).compareTo(ttl) < 0) {
            return cached.keys();
        }

        try {
            TenantKeys keys = controlDb.queryForObject(SQL,
                    (rs, i) -> new TenantKeys(rs.getString("data_kek_id"), rs.getString("audit_kek_id")),
                    tenantId);
            if (keys != null) {
                cache.put(tenantId, new CachedKeys(keys, now));
                return keys;
            }
        } catch (RuntimeException e) {
            LOG.warn("No se pudo leer las KEK del tenant {}: {}", tenantId, e.getClass().getSimpleName());
        }
        
        // Return cached even if expired, or throw if not found
        if (cached != null) {
            return cached.keys();
        }
        throw new TenantContextMissingException("No se encontraron KEKs para el tenant " + tenantId);
    }
}
