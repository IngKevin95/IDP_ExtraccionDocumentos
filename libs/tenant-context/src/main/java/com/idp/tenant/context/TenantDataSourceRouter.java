package com.idp.tenant.context;

import com.idp.tenant.TenantContext;
import com.idp.tenant.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * DataSource que enruta cada conexion al silo del tenant del contexto actual.
 * Crea un pool Hikari por tenant bajo demanda con credenciales de {@link TenantCredentialProvider},
 * acota el numero de pools y desaloja el menos recientemente usado (LRU).
 * El desalojo es suave (H7): un pool retirado con conexiones activas no se cierra hasta que se
 * liberen (o venza {@link #setDrainTimeout}); asi no se corta una transaccion en curso.
 */
public final class TenantDataSourceRouter extends AbstractRoutingDataSource implements DisposableBean {

    public static final int DEFAULT_MAX_POOLS = 50;
    public static final int DEFAULT_POOL_SIZE = 5;

    private final TenantCredentialProvider credentialProvider;
    private final Function<TenantConnection, DataSource> poolFactory;
    private final int maxPools;
    private final Map<String, DataSource> pools = new LinkedHashMap<>(16, 0.75f, true);
    private final ToIntFunction<DataSource> activeConnections;
    private final List<Draining> draining = new ArrayList<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tenant-pool-drain");
        t.setDaemon(true);
        return t;
    });
    private volatile Duration drainTimeout = Duration.ofMinutes(5);

    private record Draining(DataSource ds, long deadlineNanos) {
    }

    public TenantDataSourceRouter(TenantCredentialProvider credentialProvider, int maxPools, int poolSize) {
        this(credentialProvider, maxPools, hikariFactory(poolSize));
    }

    public TenantDataSourceRouter(TenantCredentialProvider credentialProvider, int maxPools,
                                  Function<TenantConnection, DataSource> poolFactory) {
        this(credentialProvider, maxPools, poolFactory, TenantDataSourceRouter::hikariActive);
    }

    /** {@code activeConnections}: conexiones en uso de un pool (por defecto Hikari; otros tipos cuentan 0). */
    public TenantDataSourceRouter(TenantCredentialProvider credentialProvider, int maxPools,
                                  Function<TenantConnection, DataSource> poolFactory,
                                  ToIntFunction<DataSource> activeConnections) {
        if (maxPools < 1) {
            throw new IllegalArgumentException("maxPools debe ser >= 1");
        }
        this.credentialProvider = Objects.requireNonNull(credentialProvider);
        this.poolFactory = Objects.requireNonNull(poolFactory);
        this.maxPools = maxPools;
        this.activeConnections = Objects.requireNonNull(activeConnections);
        setTargetDataSources(new HashMap<>());
        afterPropertiesSet();
        reaper.scheduleWithFixedDelay(this::reapDraining, 2, 2, TimeUnit.SECONDS);
    }

    private static int hikariActive(DataSource ds) {
        if (ds instanceof HikariDataSource h && h.getHikariPoolMXBean() != null) {
            return h.getHikariPoolMXBean().getActiveConnections();
        }
        return 0;
    }

    public void setDrainTimeout(Duration drainTimeout) {
        this.drainTimeout = Objects.requireNonNull(drainTimeout);
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
            retire(eldest.getValue());
        }
    }

    /** Descarta el pool de un tenant (rotacion de credenciales, baja, suspension). */
    public synchronized void evict(String tenantId) {
        DataSource removed = pools.remove(tenantId);
        if (removed != null) {
            retire(removed);
        }
    }

    /** Cierra ya si no hay conexiones activas; si las hay, el pool queda drenando hasta que se liberen. */
    private void retire(DataSource ds) {
        if (activeConnections.applyAsInt(ds) <= 0) {
            closeQuietly(ds);
            return;
        }
        draining.add(new Draining(ds, System.nanoTime() + drainTimeout.toNanos()));
    }

    synchronized void reapDraining() {
        long now = System.nanoTime();
        draining.removeIf(d -> {
            if (activeConnections.applyAsInt(d.ds()) <= 0 || now - d.deadlineNanos() >= 0) {
                closeQuietly(d.ds());
                return true;
            }
            return false;
        });
    }

    public synchronized int drainingCount() {
        return draining.size();
    }

    public synchronized int poolCount() {
        return pools.size();
    }

    @Override
    public synchronized void destroy() {
        reaper.shutdownNow();
        pools.values().forEach(TenantDataSourceRouter::closeQuietly);
        pools.clear();
        draining.forEach(d -> closeQuietly(d.ds()));
        draining.clear();
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
