package com.idp.storage;

import com.idp.kms.EnvelopeCiphertext;
import com.idp.kms.EnvelopeCrypto;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lector/escritor de artefactos cifrados con sobre (DEK por objeto envuelta con la KEK del tenant). El AAD liga el
 * cifrado a tenant, documento, tipo de artefacto y pagina, de modo que productor y consumidores comparten contrato.
 */
public final class EncryptedArtifactStore {

    public static final String NOT_FOUND_MESSAGE = "Objeto no encontrado";

    private final ObjectStore store;
    private final EnvelopeCrypto crypto;
    private final com.idp.tenant.context.TenantKeyResolver keyResolver;

    public EncryptedArtifactStore(ObjectStore store, EnvelopeCrypto crypto, com.idp.tenant.context.TenantKeyResolver keyResolver) {
        this.store = store;
        this.crypto = crypto;
        this.keyResolver = keyResolver;
    }

    /** Cifra y guarda el artefacto en su ruta canonica; devuelve la ruta. */
    public String put(String tenantId, UUID documentId, ArtifactKind kind, int page, byte[] plain) {
        String key = ArtifactPaths.key(kind, documentId, page);
        String kekId = keyResolver.resolve(tenantId).dataKekId();
        EnvelopeCiphertext env = crypto.encrypt(new TenantId(tenantId), kekId, aad(tenantId, documentId, kind, page),
                plain);
        byte[] bytes = env.toBytes();
        store.put(new TenantId(tenantId), key, new ByteArrayInputStream(bytes),
                new ObjectMetadata(sha256(bytes), bytes.length, "application/octet-stream"));
        return key;
    }

    /** @throws ObjectStore.StorageException si el objeto no existe o no se puede leer */
    public byte[] get(String tenantId, UUID documentId, ArtifactKind kind, int page) {
        return get(tenantId, documentId, kind, page, Integer.MAX_VALUE);
    }

    /** Como {@link #get} pero rechaza objetos cifrados de mas de {@code maxEncryptedBytes}. */
    public byte[] get(String tenantId, UUID documentId, ArtifactKind kind, int page, int maxEncryptedBytes) {
        byte[] raw;
        try (InputStream in = store.get(new TenantId(tenantId), ArtifactPaths.key(kind, documentId, page))) {
            raw = in.readNBytes(maxEncryptedBytes == Integer.MAX_VALUE ? maxEncryptedBytes : maxEncryptedBytes + 1);
        } catch (IOException e) {
            throw new ObjectStore.StorageException("No se pudo leer el objeto");
        }
        if (raw.length > maxEncryptedBytes) {
            throw new ObjectStore.StorageException("Objeto excede el tamano maximo permitido");
        }
        String kekId = keyResolver.resolve(tenantId).dataKekId();
        return crypto.decrypt(new TenantId(tenantId), kekId, aad(tenantId, documentId, kind, page),
                EnvelopeCiphertext.fromBytes(raw));
    }

    /** Como {@link #get} pero devuelve vacio si el objeto no existe. */
    public Optional<byte[]> find(String tenantId, UUID documentId, ArtifactKind kind, int page, int maxEncryptedBytes) {
        try {
            return Optional.of(get(tenantId, documentId, kind, page, maxEncryptedBytes));
        } catch (ObjectStore.StorageException e) {
            if (NOT_FOUND_MESSAGE.equals(e.getMessage())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void delete(String tenantId, String key) {
        store.delete(new TenantId(tenantId), key);
    }

    /** Contexto AAD canonico: tenant, documento, tipo y pagina (0 si no aplica). */
    public static Map<String, String> aad(String tenantId, UUID documentId, ArtifactKind kind, int page) {
        return Map.of("tenantId", tenantId, "documentId", documentId.toString(), "artifact", kind.code(),
                "page", String.valueOf(kind == ArtifactKind.PAGE_PNG ? page : 0));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
