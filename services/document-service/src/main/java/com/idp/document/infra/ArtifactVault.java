package com.idp.document.infra;

import com.idp.document.config.DocumentProperties;
import com.idp.kms.EnvelopeCrypto;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Guarda y lee artefactos en el bucket del tenant. Delega en {@link EncryptedArtifactStore} (libs/storage-port) para
 * que rutas y AAD sean las mismas que usan los consumidores (extraction-service).
 */
@Component
public class ArtifactVault {

    private final EncryptedArtifactStore store;

    public ArtifactVault(ObjectStore objects, EnvelopeCrypto crypto, com.idp.tenant.context.TenantKeyResolver keyResolver) {
        this.store = new EncryptedArtifactStore(objects, crypto, keyResolver);
    }

    public String putOriginal(String tenantId, UUID documentId, byte[] plain) {
        return store.put(tenantId, documentId, ArtifactKind.ORIGINAL, 0, plain);
    }

    public String putPage(String tenantId, UUID documentId, int page, byte[] png) {
        return store.put(tenantId, documentId, ArtifactKind.PAGE_PNG, page, png);
    }

    public String putTextLayer(String tenantId, UUID documentId, byte[] json) {
        return store.put(tenantId, documentId, ArtifactKind.TEXT_LAYER, 0, json);
    }

    public byte[] getOriginal(String tenantId, UUID documentId) {
        return store.get(tenantId, documentId, ArtifactKind.ORIGINAL, 0);
    }

    public void delete(String tenantId, String key) {
        store.delete(tenantId, key);
    }
}
