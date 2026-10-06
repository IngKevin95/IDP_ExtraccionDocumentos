package com.idp.notification.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tope de envios simultaneos por tenant y por host receptor: un tenant lento no acapara el pool de envio y un
 * tercero no recibe mas de {@code perHost} conexiones a la vez desde este servicio.
 */
public final class ConcurrencyLimiter {

    private final int perTenant;
    private final int perHost;
    private final ConcurrentMap<String, Semaphore> tenants = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Semaphore> hosts = new ConcurrentHashMap<>();

    public ConcurrencyLimiter(int perTenant, int perHost) {
        if (perTenant < 1 || perHost < 1) {
            throw new IllegalArgumentException("Los topes de concurrencia deben ser >= 1");
        }
        this.perTenant = perTenant;
        this.perHost = perHost;
    }

    /** Cupo concedido; {@link #release()} es idempotente. */
    public static final class Permit {
        private final Semaphore tenant;
        private final Semaphore host;
        private final AtomicBoolean released = new AtomicBoolean();

        Permit(Semaphore tenant, Semaphore host) {
            this.tenant = tenant;
            this.host = host;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                host.release();
                tenant.release();
            }
        }
    }

    /**
     * Espera hasta {@code wait} por un cupo de tenant y uno de host.
     *
     * @return el cupo, o null si no hubo capacidad a tiempo
     */
    public Permit tryAcquire(String tenant, String host, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        Semaphore t = tenants.computeIfAbsent(tenant, k -> new Semaphore(perTenant, true));
        if (!t.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
            return null;
        }
        Semaphore h = hosts.computeIfAbsent(host, k -> new Semaphore(perHost, true));
        long remaining = Math.max(0, deadline - System.nanoTime());
        if (!h.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
            t.release();
            return null;
        }
        return new Permit(t, h);
    }
}
