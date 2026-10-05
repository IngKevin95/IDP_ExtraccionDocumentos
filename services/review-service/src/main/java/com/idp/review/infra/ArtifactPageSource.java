package com.idp.review.infra;

import com.idp.kms.EnvelopeCrypto;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import com.idp.tenant.context.TenantKeyResolver;
import java.util.Optional;
import java.util.UUID;

/**
 * Lee las paginas PNG que escribio el document-service con {@link EncryptedArtifactStore} (mismas rutas y AAD), de modo
 * que el bucket del tenant y su KEK se resuelven igual que en los demas servicios.
 */
public class ArtifactPageSource implements PageImageSource {

    private final EncryptedArtifactStore store;
    private final int maxEncryptedBytes;

    public ArtifactPageSource(ObjectStore objects, EnvelopeCrypto crypto, TenantKeyResolver keys,
                              int maxEncryptedBytes) {
        this.store = new EncryptedArtifactStore(objects, crypto, keys);
        this.maxEncryptedBytes = maxEncryptedBytes;
    }

    @Override
    public Optional<byte[]> page(String tenantId, UUID documentId, int page) {
        return store.find(tenantId, documentId, ArtifactKind.PAGE_PNG, page, maxEncryptedBytes);
    }
}
