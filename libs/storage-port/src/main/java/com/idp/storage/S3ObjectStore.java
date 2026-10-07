package com.idp.storage;

import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.InputStream;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Consumer;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * ObjectStore sobre cualquier almacen compatible con S3 (AWS, MinIO, Ceph, LocalStack). Cada tenant usa su
 * bucket (silo); todo objeto se guarda ademas bajo el prefijo del tenant ({@code <tenantId>/<path>}); el SHA-256 declarado lo verifica
 * el servidor al recibir el objeto.
 */
public class S3ObjectStore implements ObjectStore {

    protected final S3Client s3;
    protected final TenantBucketResolver buckets;

    public S3ObjectStore(S3Client s3, String bucket) {
        this(s3, TenantBucketResolver.fixed(bucket));
    }

    /** Bucket por tenant (silo): se resuelve desde silo_location; el prefijo del tenant se mantiene. */
    public S3ObjectStore(S3Client s3, TenantBucketResolver buckets) {
        this.s3 = s3;
        this.buckets = buckets;
    }

    protected final String bucket(TenantId tenantId) {
        return buckets.resolve(tenantId.value());
    }

    /** Clave del objeto con aislamiento por tenant; rechaza rutas que escapen del prefijo. */
    protected static String key(TenantId tenantId, String path) {
        return ObjectKeys.of(tenantId, path);
    }

    @Override
    public void put(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata) {
        putInternal(tenantId, path, data, metadata, b -> { });
    }

    protected final void putInternal(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata,
                                     Consumer<PutObjectRequest.Builder> customizer) {
        String key = key(tenantId, path);
        if (metadata == null || metadata.length() < 0) {
            throw new StorageException("Metadatos de objeto invalidos");
        }
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket(tenantId)).key(key)
            .contentLength(metadata.length());
        if (metadata.contentType() != null) {
            req.contentType(metadata.contentType());
        }
        if (metadata.sha256() != null) {
            req.checksumSHA256(toBase64(metadata.sha256()));
        }
        customizer.accept(req);
        try {
            s3.putObject(req.build(), RequestBody.fromInputStream(data, metadata.length()));
        } catch (SdkException e) {
            throw new StorageException("No se pudo guardar el objeto: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public InputStream get(TenantId tenantId, String path) {
        String key = key(tenantId, path);
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket(tenantId)).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new StorageException("Objeto no encontrado");
        } catch (SdkException e) {
            throw new StorageException("No se pudo leer el objeto: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void delete(TenantId tenantId, String path) {
        String key = key(tenantId, path);
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket(tenantId)).key(key).build());
        } catch (SdkException e) {
            throw new StorageException("No se pudo borrar el objeto: " + e.getClass().getSimpleName());
        }
    }

    private static String toBase64(String hexSha256) {
        try {
            byte[] raw = HexFormat.of().parseHex(hexSha256);
            if (raw.length != 32) {
                throw new StorageException("SHA-256 invalido");
            }
            return Base64.getEncoder().encodeToString(raw);
        } catch (IllegalArgumentException e) {
            throw new StorageException("SHA-256 invalido");
        }
    }
}
