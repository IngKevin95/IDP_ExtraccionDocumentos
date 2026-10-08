package com.idp.storage.gcs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** AC-06 (todo fallo del proveedor es StorageException), tope de tamano y rigor del fake. */
class GcsObjectStoreTest {

    private final TenantId tenant = new TenantId("t1");
    private final ObjectMetadata meta = new ObjectMetadata(null, 3, "text/plain");

    private GcsObjectStore store(GcsBlobApi api, long max) {
        return new GcsObjectStore(api, TenantBucketResolver.fixed("inexistente"), max);
    }

    @Test
    void putYDeleteContraBucketInexistenteSalenComoStorageException() {
        GcsObjectStore store = store(new InMemoryGcsBlobApi(), 1024);
        StorageException put = assertThrows(StorageException.class,
            () -> store.put(tenant, "a.txt", new ByteArrayInputStream(new byte[3]), meta));
        StorageException del = assertThrows(StorageException.class, () -> store.delete(tenant, "a.txt"));
        assertEquals(-1, put.getMessage().indexOf("t1"));
        assertEquals(-1, del.getMessage().indexOf("t1"));
    }

    @Test
    void elTopeDeTamanoSeValidaAntesDeLeerElStream() {
        InputStream noDebeLeerse = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("se leyo el stream");
            }
        };
        GcsObjectStore store = store(new InMemoryGcsBlobApi().withBucket("inexistente"), 2);
        StorageException ex = assertThrows(StorageException.class,
            () -> store.put(tenant, "a.txt", noDebeLeerse, meta));
        assertEquals("El objeto excede el tamano maximo permitido", ex.getMessage());
    }

    @Test
    void unObjetoEnElTopeSeAcepta() {
        GcsObjectStore store = store(new InMemoryGcsBlobApi().withBucket("inexistente"), 3);
        assertDoesNotThrow(() -> store.put(tenant, "a.txt", new ByteArrayInputStream(new byte[3]), meta));
    }

    @Test
    void elFakeRechazaRetencionSinRetencionPorObjetoOEnElPasado() {
        InMemoryGcsBlobApi api = new InMemoryGcsBlobApi().withBucket("plano").withObjectRetention("worm");
        Instant futuro = api.clock.instant().plusSeconds(60);
        assertThrows(GcsBlobApi.BlobApiException.class, () -> api.write("plano", "k", new byte[1], null, futuro));
        assertThrows(GcsBlobApi.BlobApiException.class,
            () -> api.write("worm", "k", new byte[1], null, api.clock.instant().minusSeconds(1)));
        assertThrows(GcsBlobApi.BlobNotFoundException.class, () -> api.read("otro", "k"));
        assertDoesNotThrow(() -> api.write("worm", "k", new byte[1], null, futuro));
    }
}
