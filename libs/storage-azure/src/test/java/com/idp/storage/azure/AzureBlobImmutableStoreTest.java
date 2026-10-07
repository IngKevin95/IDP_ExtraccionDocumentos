package com.idp.storage.azure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.storage.contract.ImmutableStoreContract;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Contrato WORM (AC-04..AC-09) contra el fake de {@link BlobApi}: Azurite no cubre immutability (plan T-00). */
class AzureBlobImmutableStoreTest extends ImmutableStoreContract {

    private static final String CONTAINER = "idp-worm";

    private final MutableClock clock = new MutableClock();
    private final InMemoryBlobApi api = new InMemoryBlobApi(clock);
    private final AzureBlobImmutableStore store;

    AzureBlobImmutableStoreTest() {
        api.createContainer(CONTAINER, true);
        store = AzureBlobImmutableStore.forContainer(api, CONTAINER, clock);
    }

    @Override
    protected ImmutableStore getStore() {
        return store;
    }

    @Override
    protected TenantId getTenantA() {
        return new TenantId("t1");
    }

    @Override
    protected TenantId getTenantB() {
        return new TenantId("t2");
    }

    @Override
    protected void advancePastRetention() {
        clock.advance(Duration.ofSeconds(2));
    }

    @Override
    protected void hardDelete(TenantId tenantId, String path) {
        api.hardDelete(CONTAINER, tenantId.value() + "/" + path);
    }

    @Override
    protected byte[] retainedContent(TenantId tenantId, String path) {
        return api.retainedContent(CONTAINER, tenantId.value() + "/" + path);
    }

    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> api.failing = true);
    }

    @Test
    void contenedorSinWormPorVersionHaceFallarElArranque() {
        api.createContainer("sin-worm", false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AzureBlobImmutableStore.forContainer(api, "sin-worm", clock));
        assertEquals(true, ex.getMessage().contains("immutable storage con versionado"));
    }

    @Test
    void contenedorInexistenteHaceFallarElArranque() {
        assertThrows(IllegalStateException.class, () -> AzureBlobImmutableStore.forContainer(api, "no-existe", clock));
    }

    @Test
    void conResolverPorTenantLaValidacionOcurreAlPrimerUsoDelContenedor() {
        api.createContainer("silo-sin-worm", false);
        AzureBlobImmutableStore porTenant =
            new AzureBlobImmutableStore(api, TenantBucketResolver.fixed("silo-sin-worm"), clock);
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> porTenant.putWithRetention(getTenantA(), "a.txt",
            new ByteArrayInputStream(data), new ObjectMetadata(null, data.length, null), Duration.ofDays(1)));
    }

    @Test
    void retencionNoPositivaSeRechaza() {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(StorageException.class, () -> store.putWithRetention(getTenantA(), "a.txt",
            new ByteArrayInputStream(data), new ObjectMetadata(null, data.length, null), Duration.ZERO));
    }

    @Test
    void sha256DistintoDelContenidoRechazaLaEscritura() {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        String otroHash = "00".repeat(32);
        assertThrows(StorageException.class, () -> store.put(getTenantA(), "bad.txt",
            new ByteArrayInputStream(data), new ObjectMetadata(otroHash, data.length, null)));
        assertThrows(StorageException.class, () -> store.get(getTenantA(), "bad.txt"));
    }

    @Test
    void sha256MalFormadoSeRechazaSinTocarElProveedor() {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        api.failing = true;
        StorageException ex = assertThrows(StorageException.class, () -> store.put(getTenantA(), "a.txt",
            new ByteArrayInputStream(data), new ObjectMetadata("zz", data.length, null)));
        assertEquals("SHA-256 invalido", ex.getMessage());
    }

    @Test
    void legalHoldSobreObjetoInexistenteFalla() {
        assertThrows(StorageException.class, () -> store.applyLegalHold(getTenantA(), "no.txt"));
        assertDoesNotThrow(() -> store.delete(getTenantA(), "no.txt"));
    }
}
