package com.idp.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.extraction.store.DocumentStoragePort.DocumentNotRenderedException;
import com.idp.extraction.store.EncryptedDocumentStorageAdapter;
import com.idp.extraction.store.PageContent;
import com.idp.extraction.store.RenderedDocument;
import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.InMemoryKeyService;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Productor (mismo contrato que usa document-service: {@code EncryptedArtifactStore} con KEK "documents") y lector
 * de extraction-service sobre el mismo bucket y las mismas rutas canonicas.
 */
class EncryptedDocumentStorageAdapterTest {

    private static final class MemoryStore implements ObjectStore {
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

    private final MemoryStore bucket = new MemoryStore();
    private final EnvelopeCrypto crypto = new EnvelopeCrypto(new InMemoryKeyService());
    private final EncryptedArtifactStore producer = new EncryptedArtifactStore(bucket, crypto, "documents");
    private final EncryptedDocumentStorageAdapter reader = new EncryptedDocumentStorageAdapter(
        new EncryptedArtifactStore(bucket, crypto, "documents"), new ObjectMapper(), 100);
    private final String tenant = UUID.randomUUID().toString();
    private final UUID doc = UUID.randomUUID();

    @Test
    void readsPagesAndNativeTextWrittenByDocumentService() {
        producer.put(tenant, doc, ArtifactKind.PAGE_PNG, 1, new byte[] {1, 2, 3});
        producer.put(tenant, doc, ArtifactKind.PAGE_PNG, 2, new byte[] {4, 5});
        producer.put(tenant, doc, ArtifactKind.TEXT_LAYER, 0,
            "{\"pages\":[{\"page\":1,\"text\":\"OFICIO 123\"},{\"page\":2,\"text\":\"\"}]}"
                .getBytes(StandardCharsets.UTF_8));

        RenderedDocument rendered = reader.load(new TenantId(tenant), doc);

        assertThat(rendered.pages()).hasSize(2);
        PageContent p1 = rendered.pages().get(0);
        assertThat(p1.png()).containsExactly(1, 2, 3);
        assertThat(p1.nativeText()).isEqualTo("OFICIO 123");
        assertThat(rendered.pages().get(1).nativeText()).isNull();
    }

    @Test
    void textLayerIsOptional() {
        producer.put(tenant, doc, ArtifactKind.PAGE_PNG, 1, new byte[] {1});
        assertThat(reader.load(new TenantId(tenant), doc).pages().get(0).nativeText()).isNull();
    }

    @Test
    void documentWithoutPagesIsNotRendered() {
        assertThatThrownBy(() -> reader.load(new TenantId(tenant), doc))
            .isInstanceOf(DocumentNotRenderedException.class);
    }

    @Test
    void otherTenantCannotReadArtifacts() {
        producer.put(tenant, doc, ArtifactKind.PAGE_PNG, 1, new byte[] {1});
        assertThatThrownBy(() -> reader.load(new TenantId(UUID.randomUUID().toString()), doc))
            .isInstanceOf(DocumentNotRenderedException.class);
    }
}
