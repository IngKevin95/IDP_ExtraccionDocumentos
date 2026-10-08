package com.idp.storage.azure;

import com.idp.storage.StorageKeys;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.InputStream;
import java.time.Instant;
import java.util.HexFormat;

/**
 * ObjectStore sobre Azure Blob Storage. Cada tenant usa su contenedor (silo); el blob se guarda bajo el prefijo
 * del tenant ({@code <tenantId>/<path>}). El SHA-256 declarado se verifica antes de confirmar el blob.
 */
public class AzureBlobObjectStore implements ObjectStore {

    protected final BlobApi api;
    protected final TenantBucketResolver containers;

    public AzureBlobObjectStore(BlobApi api, TenantBucketResolver containers) {
        this.api = api;
        this.containers = containers;
    }

    protected final String container(TenantId tenantId) {
        return containers.resolve(tenantId.value());
    }

    @Override
    public void put(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata) {
        putInternal(tenantId, path, data, metadata, null);
    }

    protected final void putInternal(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata,
                                     Instant retainUntil) {
        String blob = StorageKeys.key(tenantId, path);
        if (metadata == null || metadata.length() < 0) {
            throw new StorageException("Metadatos de objeto invalidos");
        }
        byte[] sha256 = metadata.sha256() == null ? null : parseSha256(metadata.sha256());
        try {
            api.write(container(tenantId), blob, data, metadata.length(), metadata.contentType(), sha256,
                retainUntil);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StorageException("No se pudo guardar el objeto: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public InputStream get(TenantId tenantId, String path) {
        String blob = StorageKeys.key(tenantId, path);
        try {
            return api.read(container(tenantId), blob);
        } catch (BlobApi.NotFoundException e) {
            throw new StorageException("Objeto no encontrado");
        } catch (RuntimeException e) {
            throw new StorageException("No se pudo leer el objeto: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void delete(TenantId tenantId, String path) {
        String blob = StorageKeys.key(tenantId, path);
        try {
            api.delete(container(tenantId), blob);
        } catch (RuntimeException e) {
            throw new StorageException("No se pudo borrar el objeto: " + e.getClass().getSimpleName(), e);
        }
    }

    private static byte[] parseSha256(String hexSha256) {
        try {
            byte[] raw = HexFormat.of().parseHex(hexSha256);
            if (raw.length != 32) {
                throw new StorageException("SHA-256 invalido");
            }
            return raw;
        } catch (IllegalArgumentException e) {
            throw new StorageException("SHA-256 invalido");
        }
    }
}
