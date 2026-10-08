package com.idp.storage.gcs;

import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.storage.StorageKeys;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * ObjectStore sobre Google Cloud Storage. Mismo aislamiento que {@code S3ObjectStore}: un bucket por tenant (silo) y
 * clave {@code <tenantId>/<path>}. GCS no verifica SHA-256 en el servidor, asi que el hash declarado se comprueba
 * aqui antes de subir; por eso el contenido se lee completo en memoria, con tope {@code maxObjectBytes} validado
 * antes de leer. {@code create} sin precondicion no es idempotente y el SDK no lo reintenta: un fallo transitorio
 * llega al llamador como {@link StorageException}.
 */
public class GcsObjectStore implements ObjectStore {

    protected final GcsBlobApi api;
    protected final TenantBucketResolver buckets;
    private final long maxObjectBytes;

    public static final long DEFAULT_MAX_OBJECT_BYTES = 256L * 1024 * 1024;

    public GcsObjectStore(GcsBlobApi api, TenantBucketResolver buckets) {
        this(api, buckets, DEFAULT_MAX_OBJECT_BYTES);
    }

    public GcsObjectStore(GcsBlobApi api, TenantBucketResolver buckets, long maxObjectBytes) {
        this.api = api;
        this.buckets = buckets;
        this.maxObjectBytes = Math.min(maxObjectBytes, Integer.MAX_VALUE - 8L);
    }

    protected final String bucket(TenantId tenantId) {
        return buckets.resolve(tenantId.value());
    }

    @Override
    public void put(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata) {
        putInternal(tenantId, path, data, metadata, null);
    }

    protected final void putInternal(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata,
                                     Instant retainUntil) {
        String key = StorageKeys.key(tenantId, path);
        if (metadata == null || metadata.length() < 0) {
            throw new StorageException("Metadatos de objeto invalidos");
        }
        if (metadata.length() > maxObjectBytes) {
            throw new StorageException("El objeto excede el tamano maximo permitido");
        }
        byte[] declared = metadata.sha256() == null ? null : StorageKeys.sha256Bytes(metadata.sha256());
        byte[] content = readExactly(data, (int) metadata.length());
        if (declared != null && !MessageDigest.isEqual(declared, sha256(content))) {
            throw new StorageException("El SHA-256 declarado no coincide con el contenido");
        }
        try {
            api.write(bucket(tenantId), key, content, metadata.contentType(), retainUntil);
        } catch (GcsBlobApi.BlobApiException e) {
            throw new StorageException("No se pudo guardar el objeto: " + e.getMessage());
        }
    }

    @Override
    public InputStream get(TenantId tenantId, String path) {
        String key = StorageKeys.key(tenantId, path);
        try {
            return new ByteArrayInputStream(api.read(bucket(tenantId), key));
        } catch (GcsBlobApi.BlobNotFoundException e) {
            throw new StorageException("Objeto no encontrado");
        } catch (GcsBlobApi.BlobApiException e) {
            throw new StorageException("No se pudo leer el objeto: " + e.getMessage());
        }
    }

    @Override
    public void delete(TenantId tenantId, String path) {
        String key = StorageKeys.key(tenantId, path);
        try {
            api.delete(bucket(tenantId), key);
        } catch (GcsBlobApi.BlobApiException e) {
            throw new StorageException("No se pudo borrar el objeto: " + e.getMessage());
        }
    }

    private static byte[] readExactly(InputStream data, int length) {
        try {
            byte[] content = data.readNBytes(length + 1);
            if (content.length != length) {
                throw new StorageException("La longitud declarada no coincide con el contenido");
            }
            return content;
        } catch (IOException e) {
            throw new StorageException("No se pudo leer el contenido a guardar");
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
