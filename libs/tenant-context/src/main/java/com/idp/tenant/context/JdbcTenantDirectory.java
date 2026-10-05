package com.idp.tenant.context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Directorio sobre la base de control de la plataforma (tenant-service): tenants en estado ACTIVE con fila en
 * {@code silo_location}. Cache corta; si la base de control falla se devuelve la ultima lista conocida.
 */
public final class JdbcTenantDirectory implements TenantDirectory {

    static final String SQL = "select cast(t.id as varchar(64)) from tenants t "
        + "join silo_location s on s.tenant_id = t.id where t.status = 'ACTIVE' order by t.id";

    private static final Logger LOG = LoggerFactory.getLogger(JdbcTenantDirectory.class);

    private final JdbcTemplate controlDb;
    private final Duration ttl;
    private final Clock clock;
    private volatile List<String> cached = List.of();
    private volatile Instant loadedAt;

    public JdbcTenantDirectory(JdbcTemplate controlDb, Duration ttl, Clock clock) {
        this.controlDb = controlDb;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public List<String> activeTenants() {
        Instant now = clock.instant();
        Instant at = loadedAt;
        if (at != null && Duration.between(at, now).compareTo(ttl) < 0) {
            return cached;
        }
        try {
            cached = List.copyOf(controlDb.queryForList(SQL, String.class));
            loadedAt = now;
        } catch (RuntimeException e) {
            LOG.warn("No se pudo leer el directorio de tenants: {}", e.getClass().getSimpleName());
        }
        return cached;
    }
}
