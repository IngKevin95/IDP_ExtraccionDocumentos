package com.idp.tenant.context;

import com.idp.tenant.TenantContext;
import com.idp.tenant.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * DataSource que enruta cada conexion al silo del tenant del contexto actual.
 * Crea un pool Hikari por tenant bajo demanda con credenciales de {@link TenantCredentialProvider},
 * acota el numero de pools y desaloja el menos recientemente usado (LRU).
 */
public final class TenantDataSourceRouter extends AbstractRoutingDataSource implements DisposableBean {

    public static final int DEFAULT_MAX_POOLS = 50;
    public static final int DEFAULT_POOL_SIZE = 5;

    private final TenantCredentialProvider credentialProvider;
    private final Function<TenantConnection, DataSource> poolFactory;
    private final int maxPools;
    private final Map<String, DataSource> pools = new LinkedHashMap<>(16, 0.75f, true);

    public TenantDataSourceRouter(TenantCredentialProvider credentialProvider, int maxPools, int poolSize) {
        this(credentialProvider, maxPools, hikariFactory(poolSize));
    }

    public TenantDataSourceRouter(TenantCredentialProvider credentialProvider, int maxPools,
                                  Function<TenantConnection, DataSource> poolFactory) {
        if (maxPools < 1) {
            throw new IllegalArgumentException("maxPools debe ser >= 1");
        }
        this.credentialProvider = Objects.requireNonNull(credentialProvider);
        this.poolFactory = Objects.requireNonNull(poolFactory);
        this.maxPools = maxPools;
        setTargetDataSources(new HashMap<>());
        afterPropertiesSet();
    }

    private static Function<TenantConnection, DataSource> hikariFactory(int poolSize) {
        return c -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(c.jdbcUrl());
            cfg.setUsername(c.username());
            cfg.setPassword(c.password());
            cfg.setMaximumPoolSize(poolSize);
            cfg.setMinimumIdle(0);
            return new HikariDataSource(cfg);
        };
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String fromHolder = TenantContextHolder.getTenantId();
        if (fromHolder != null && !fromHolder.isBlank()) {
            return fromHolder;
        }
        TenantId fromContext = TenantContext.getTenantId();
        if (fromContext != null) {
            return fromContext.value();
        }
        throw new TenantContextMissingException();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        return poolFor((String) determineCurrentLookupKey());
    }

    synchronized DataSource poolFor(String tenantId) {
        DataSource existing = pools.get(tenantId);
        if (existing != null) {
            return existing;
        }
        TenantConnection conn = credentialProvider.resolve(tenantId);
        DataSource created = poolFactory.apply(conn);
        pools.put(tenantId, created);
        evictOverflow();
        return created;
    }

    private void evictOverflow() {
        Iterator<Map.Entry<String, DataSource>> it = pools.entrySet().iterator();
        while (pools.size() > maxPools && it.hasNext()) {
            Map.Entry<String, DataSource> eldest = it.next();
            it.remove();
            closeQuietly(eldest.getValue());
        }
    }

    /** Descarta el pool de un tenant (rotacion de credenciales, baja, suspension). */
    public synchronized void evict(String tenantId) {
        DataSource removed = pools.remove(tenantId);
        if (removed != null) {
            closeQuietly(removed);
        }
    }

    public synchronized int poolCount() {
        return pools.size();
    }

    @Override
    public synchronized void destroy() {
        pools.values().forEach(TenantDataSourceRouter::closeQuietly);
        pools.clear();
    }

    private static void closeQuietly(DataSource ds) {
        if (ds instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // cierre best-effort
            }
        }
    }
}
