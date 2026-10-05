package com.idp.tenant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.idp.tenant.TenantContext;
import com.idp.tenant.TenantId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantDataSourceRouterTest {

    private final List<String> resolved = new ArrayList<>();
    private final List<DataSource> created = new ArrayList<>();

    private final TenantCredentialProvider provider = tenantId -> {
        resolved.add(tenantId);
        if ("suspendido".equals(tenantId)) {
            throw new TenantNotAvailableException("suspendido");
        }
        return new TenantConnection("jdbc:test://" + tenantId, "u-" + tenantId, "p");
    };

    private DataSource newPool(TenantConnection c) {
        DataSource ds = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class)
            .name(c.jdbcUrl()));
        created.add(ds);
        return ds;
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        TenantContext.clear();
    }

    @Test
    void ac02_sinTenantEnContextoLanzaExcepcionDeSeguridad() {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 3, this::newPool);
        assertThrows(TenantContextMissingException.class, router::getConnection);
        assertEquals(0, resolved.size());
    }

    @Test
    void ac03_enrutaAlPoolDelTenantYLoReutiliza() throws SQLException {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 3, this::newPool);
        Connection c1 = mock(Connection.class);
        TenantContextHolder.setTenantId("t1");
        DataSource first = router.poolFor("t1");
        org.mockito.Mockito.when(first.getConnection()).thenReturn(c1);

        assertSame(c1, router.getConnection());
        assertSame(first, router.poolFor("t1"));
        assertEquals(List.of("t1"), resolved);

        TenantContextHolder.setTenantId("t2");
        assertNotSame(first, router.poolFor("t2"));
        assertEquals(List.of("t1", "t2"), resolved);
    }

    @Test
    void usaTenantContextCuandoElHolderEstaVacio() {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 3, this::newPool);
        TenantContext.setTenantId(new TenantId("tctx"));
        assertEquals("tctx", router.determineCurrentLookupKey());
    }

    @Test
    void ac08_tenantNoDisponibleNoCreaPool() {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 3, this::newPool);
        TenantContextHolder.setTenantId("suspendido");
        assertThrows(TenantNotAvailableException.class, router::getConnection);
        assertEquals(0, router.poolCount());
    }

    @Test
    void desalojaLruYCierraElPoolDesalojado() throws Exception {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 2, this::newPool);
        DataSource a = router.poolFor("a");
        router.poolFor("b");
        router.poolFor("a"); // a pasa a ser el mas reciente; b es el LRU
        router.poolFor("c");

        assertEquals(2, router.poolCount());
        verify((AutoCloseable) created.get(1)).close();
        verify((AutoCloseable) a, never()).close();
        router.poolFor("b");
        assertEquals(List.of("a", "b", "c", "b"), resolved);
    }

    @Test
    void evictYDestroyCierranPools() throws Exception {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 5, this::newPool);
        DataSource a = router.poolFor("a");
        DataSource b = router.poolFor("b");
        router.evict("a");
        verify((AutoCloseable) a).close();
        assertEquals(1, router.poolCount());
        router.destroy();
        verify((AutoCloseable) b).close();
        assertEquals(0, router.poolCount());
        router.evict("zzz");
        assertEquals(0, router.poolCount());
    }

    @Test
    void maxPoolsInvalidoSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> new TenantDataSourceRouter(provider, 0, this::newPool));
    }

    @Test
    void tenantConnectionOcultaPasswordEnToString() {
        String s = new TenantConnection("jdbc:x", "usr", "secreta").toString();
        assertEquals("TenantConnection[jdbcUrl=jdbc:x, username=usr, password=***]", s);
    }

    @Test
    void h7_evictConConexionesActivasDrenaYCierraAlLiberarse() throws Exception {
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger(2);
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 5, this::newPool, ds -> active.get());
        DataSource a = router.poolFor("a");
        router.evict("a");

        verify((AutoCloseable) a, never()).close();
        assertEquals(0, router.poolCount());
        assertEquals(1, router.drainingCount());

        router.reapDraining();
        verify((AutoCloseable) a, never()).close();

        active.set(0);
        router.reapDraining();
        verify((AutoCloseable) a).close();
        assertEquals(0, router.drainingCount());
        router.destroy();
    }

    @Test
    void h7_drenadoVencidoFuerzaElCierre() throws Exception {
        TenantDataSourceRouter router = new TenantDataSourceRouter(provider, 5, this::newPool, ds -> 3);
        router.setDrainTimeout(java.time.Duration.ZERO);
        DataSource a = router.poolFor("a");
        router.evict("a");
        router.reapDraining();
        verify((AutoCloseable) a).close();
        router.destroy();
    }
}
