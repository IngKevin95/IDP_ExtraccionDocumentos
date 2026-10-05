package com.idp.document.infra;

import com.idp.kms.EnvelopeCiphertext;
import com.idp.kms.EnvelopeCrypto;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Guarda y lee artefactos en el bucket del tenant cifrados con sobre (DEK por objeto envuelta con la KEK del
 * tenant). El AAD liga el cifrado a tenant, documento y artefacto.
 */
@Component
public class ArtifactVault {

    private final ObjectStore store;
    private final EnvelopeCrypto crypto;
    private final String kekId;

    public ArtifactVault(ObjectStore store, EnvelopeCrypto crypto,
                         com.idp.document.config.DocumentProperties props) {
        this.store = store;
        this.crypto = crypto;
        this.kekId = props.kekId();
    }

    public static String originalKey(UUID documentId) {
        return "documents/" + documentId + "/original.enc";
    }

    public static String pageKey(UUID documentId, int page) {
        return "documents/" + documentId + "/pages/page_" + page + ".png.enc";
    }

    public static String textLayerKey(UUID documentId) {
        return "documents/" + documentId + "/text_layer.json.enc";
    }

    public void put(String tenantId, UUID documentId, String key, String contentType, byte[] plain) {
        EnvelopeCiphertext env = crypto.encrypt(new TenantId(tenantId), kekId, aad(tenantId, documentId, key), plain);
        byte[] bytes = env.toBytes();
        store.put(new TenantId(tenantId), key, new ByteArrayInputStream(bytes),
                new ObjectMetadata(sha256(bytes), bytes.length, "application/octet-stream"));
    }

    public byte[] get(String tenantId, UUID documentId, String key) {
        byte[] raw;
        try (InputStream in = store.get(new TenantId(tenantId), key)) {
            raw = in.readAllBytes();
        } catch (IOException e) {
            throw new ObjectStore.StorageException("No se pudo leer el objeto");
        }
        return crypto.decrypt(new TenantId(tenantId), kekId, aad(tenantId, documentId, key),
                EnvelopeCiphertext.fromBytes(raw));
    }

    public void delete(String tenantId, String key) {
        store.delete(new TenantId(tenantId), key);
    }

    private static Map<String, String> aad(String tenantId, UUID documentId, String key) {
        return Map.of("tenantId", tenantId, "documentId", documentId.toString(), "key", key);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
