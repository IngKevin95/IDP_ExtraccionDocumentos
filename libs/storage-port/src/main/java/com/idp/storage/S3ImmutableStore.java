package com.idp.storage;

import com.idp.tenant.TenantId;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectLegalHoldRequest;

/**
 * ImmutableStore con S3 Object Lock: retencion por objeto (modo COMPLIANCE por defecto) y legal hold.
 * El bucket debe crearse con Object Lock habilitado (implica versionado). Un borrado sin versionId solo
 * agrega un delete marker: las versiones bajo retencion o legal hold no se pueden eliminar.
 */
public class S3ImmutableStore extends S3ObjectStore implements ImmutableStore {

    private final ObjectLockMode mode;
    private final Clock clock;

    public S3ImmutableStore(S3Client s3, String bucket) {
        this(s3, bucket, ObjectLockMode.COMPLIANCE, Clock.systemUTC());
    }

    public S3ImmutableStore(S3Client s3, String bucket, ObjectLockMode mode, Clock clock) {
        this(s3, com.idp.tenant.context.TenantBucketResolver.fixed(bucket), mode, clock);
    }

    public S3ImmutableStore(S3Client s3, com.idp.tenant.context.TenantBucketResolver buckets, ObjectLockMode mode,
                            Clock clock) {
        super(s3, buckets);
        this.mode = mode;
        this.clock = clock;
    }

    @Override
    public void putWithRetention(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata,
                                 Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new StorageException("La retencion debe ser positiva");
        }
        putInternal(tenantId, path, data, metadata,
            b -> b.objectLockMode(mode).objectLockRetainUntilDate(clock.instant().plus(retention)));
    }

    @Override
    public void applyLegalHold(TenantId tenantId, String path) {
        setLegalHold(tenantId, path, ObjectLockLegalHoldStatus.ON);
    }

    @Override
    public void removeLegalHold(TenantId tenantId, String path) {
        setLegalHold(tenantId, path, ObjectLockLegalHoldStatus.OFF);
    }

    private void setLegalHold(TenantId tenantId, String path, ObjectLockLegalHoldStatus status) {
        String key = key(tenantId, path);
        try {
            s3.putObjectLegalHold(PutObjectLegalHoldRequest.builder().bucket(bucket(tenantId)).key(key)
                .legalHold(h -> h.status(status)).build());
        } catch (SdkException e) {
            throw new StorageException("No se pudo cambiar el legal hold: " + e.getClass().getSimpleName());
        }
    }
}
