package com.idp.storage.gcs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.storage.contract.ImmutableStoreContract;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** AC-04..AC-08 de GcsImmutableStore contra el fake de la interfaz fina (retencion y hold con reloj controlable). */
class GcsImmutableStoreContractTest extends ImmutableStoreContract {

    private static final String BUCKET = "worm";

    private final InMemoryGcsBlobApi api = new InMemoryGcsBlobApi().withObjectRetention(BUCKET);
    private final GcsImmutableStore store = new GcsImmutableStore(api, BUCKET, api.clock);

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
        api.clock.advance(Duration.ofSeconds(2));
    }

    @Override
    protected void hardDelete(TenantId tenantId, String path) {
        try {
            api.delete(BUCKET, tenantId.value() + "/" + path);
        } catch (GcsBlobApi.BlobApiException e) {
            throw new StorageException(e.getMessage());
        }
    }

    @Override
    protected byte[] retainedContent(TenantId tenantId, String path) {
        return api.rawContent(BUCKET, tenantId.value() + "/" + path);
    }

    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> api.failing = true);
    }

    @Test
    void borrarOSobrescribirBajoRetencionFallaYElObjetoSigueLegible() throws Exception {
        byte[] original = "retenido".getBytes();
        byte[] otro = "otro".getBytes();
        store.putWithRetention(getTenantA(), "lock/r.txt", new ByteArrayInputStream(original), meta(original),
            Duration.ofMinutes(5));

        assertThrows(StorageException.class, () -> store.delete(getTenantA(), "lock/r.txt"));
        assertThrows(StorageException.class,
            () -> store.put(getTenantA(), "lock/r.txt", new ByteArrayInputStream(otro), meta(otro)));
        assertArrayEquals(original, store.get(getTenantA(), "lock/r.txt").readAllBytes());
    }

    @Test
    void vencidaLaRetencionElBorradoVuelveAFuncionar() throws Exception {
        byte[] data = "x".getBytes();
        store.putWithRetention(getTenantA(), "lock/v.txt", new ByteArrayInputStream(data), meta(data),
            Duration.ofSeconds(1));
        advancePastRetention();
        store.delete(getTenantA(), "lock/v.txt");
        assertThrows(StorageException.class, () -> store.get(getTenantA(), "lock/v.txt"));
    }

    @Test
    void retencionNoPositivaSeRechaza() {
        byte[] data = "x".getBytes();
        assertThrows(StorageException.class, () -> store.putWithRetention(getTenantA(), "lock/n.txt",
            new ByteArrayInputStream(data), meta(data), Duration.ZERO));
    }

    @Test
    void legalHoldSobreObjetoInexistenteFalla() {
        assertThrows(StorageException.class, () -> store.applyLegalHold(getTenantA(), "lock/no.txt"));
    }
}
