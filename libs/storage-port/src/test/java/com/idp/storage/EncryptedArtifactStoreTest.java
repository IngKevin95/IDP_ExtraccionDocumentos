package com.idp.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.InMemoryKeyService;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class EncryptedArtifactStoreTest {

    static final class MemoryStore implements ObjectStore {
        final Map<String, byte[]> data = new ConcurrentHashMap<>();

        @Override
        public void put(TenantId t, String path, InputStream in, ObjectMetadata m) {
            try {
                data.put(t.value() + "/" + path, in.readAllBytes());
            } catch (IOException e) {
                throw new StorageException("io");
            }
        }

        @Override
        public InputStream get(TenantId t, String path) {
            byte[] b = data.get(t.value() + "/" + path);
            if (b == null) {
                throw new StorageException("Objeto no encontrado");
            }
            return new ByteArrayInputStream(b);
        }

        @Override
        public void delete(TenantId t, String path) {
            data.remove(t.value() + "/" + path);
        }
    }

    private final MemoryStore raw = new MemoryStore();
    private final EnvelopeCrypto crypto = new EnvelopeCrypto(new InMemoryKeyService());
    private final com.idp.tenant.context.TenantKeyResolver keyResolver = org.mockito.Mockito.mock(com.idp.tenant.context.TenantKeyResolver.class);
    
    private final EncryptedArtifactStore writer;
    private final EncryptedArtifactStore reader;
    private final UUID doc = UUID.randomUUID();
    private final String tenant = UUID.randomUUID().toString();

    public EncryptedArtifactStoreTest() {
        org.mockito.Mockito.when(keyResolver.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.idp.tenant.context.TenantKeyResolver.TenantKeys("t-kek", "a-kek"));
        writer = new EncryptedArtifactStore(raw, crypto, keyResolver);
        reader = new EncryptedArtifactStore(raw, crypto, keyResolver);
    }

    @Test
    void canonicalPaths() {
        assertEquals("documents/" + doc + "/pages/page_3.png.enc", ArtifactPaths.page(doc, 3));
        assertEquals(ArtifactPaths.textLayer(doc), ArtifactPaths.key(ArtifactKind.TEXT_LAYER, doc, 0));
    }

    @Test
    void writtenArtifactIsEncryptedAndReadableByAnotherInstance() {
        byte[] png = {1, 2, 3, 4};
        String key = writer.put(tenant, doc, ArtifactKind.PAGE_PNG, 2, png);
        assertEquals(ArtifactPaths.page(doc, 2), key);
        assertTrue(raw.data.get(tenant + "/" + key).length > png.length);
        assertArrayEquals(png, reader.get(tenant, doc, ArtifactKind.PAGE_PNG, 2));
    }

    @Test
    void aadBindsPageAndDocument() {
        writer.put(tenant, doc, ArtifactKind.PAGE_PNG, 1, new byte[] {9});
        raw.data.put(tenant + "/" + ArtifactPaths.page(doc, 2), raw.data.get(tenant + "/" + ArtifactPaths.page(doc, 1)));
        assertThrows(RuntimeException.class, () -> reader.get(tenant, doc, ArtifactKind.PAGE_PNG, 2));
    }

    @Test
    void findReturnsEmptyWhenMissing() {
        assertTrue(reader.find(tenant, doc, ArtifactKind.TEXT_LAYER, 0, 1024).isEmpty());
    }

    @Test
    void oversizedObjectRejected() {
        writer.put(tenant, doc, ArtifactKind.TEXT_LAYER, 0, new byte[2000]);
        assertThrows(ObjectStore.StorageException.class, () -> reader.find(tenant, doc, ArtifactKind.TEXT_LAYER, 0, 100));
    }
}
