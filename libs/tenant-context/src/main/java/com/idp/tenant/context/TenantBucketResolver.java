package com.idp.tenant.context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bucket de almacenamiento del tenant (silo): {@code silo_location.bucket_name} en la base de control. */
public class TenantBucketResolver {
    private static final Logger LOG = LoggerFactory.getLogger(TenantBucketResolver.class);
    private static final String SQL = "select bucket_name from silo_location where tenant_id = ?";

    private final JdbcTemplate controlDb;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String bucket, Instant loadedAt) {}

    public TenantBucketResolver(JdbcTemplate controlDb, Duration ttl, Clock clock) {
        this.controlDb = controlDb;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Resolver de bucket unico (desarrollo, pruebas, bucket WORM de plataforma). */
    public static TenantBucketResolver fixed(String bucket) {
        return new TenantBucketResolver(null, Duration.ZERO, Clock.systemUTC()) {
            @Override
            public String resolve(String tenantId) {
                return bucket;
            }
        };
    }

    public String resolve(String tenantId) {
        Instant now = clock.instant();
        Cached c = cache.get(tenantId);
        if (c != null && Duration.between(c.loadedAt(), now).compareTo(ttl) < 0) {
            return c.bucket();
        }
        try {
            String bucket = controlDb.queryForObject(SQL, String.class, java.util.UUID.fromString(tenantId));
            if (bucket != null && !bucket.isBlank()) {
                cache.put(tenantId, new Cached(bucket, now));
                return bucket;
            }
        } catch (RuntimeException e) {
            LOG.warn("No se pudo leer el bucket del tenant {}: {}", tenantId, e.getClass().getSimpleName());
        }
        if (c != null) {
            return c.bucket();
        }
        throw new TenantContextMissingException("No se encontro el bucket del tenant " + tenantId);
    }
}
