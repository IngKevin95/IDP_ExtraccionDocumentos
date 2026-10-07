package com.idp.storage.gcs;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.StorageKeys;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;

/**
 * ImmutableStore con retencion por objeto en modo bloqueado y legal hold mediante {@code temporaryHold}. El bucket
 * debe crearse con retencion por objeto habilitada; se valida al construir para fallar al arrancar (ADR 0032).
 */
public final class GcsImmutableStore extends GcsObjectStore implements ImmutableStore {

    private final Clock clock;

    public GcsImmutableStore(GcsBlobApi api, String bucket) {
        this(api, bucket, Clock.systemUTC(), DEFAULT_MAX_OBJECT_BYTES);
    }

    public GcsImmutableStore(GcsBlobApi api, String bucket, Clock clock) {
        this(api, bucket, clock, DEFAULT_MAX_OBJECT_BYTES);
    }

    public GcsImmutableStore(GcsBlobApi api, String bucket, Clock clock, long maxObjectBytes) {
        super(requireWorm(api, bucket), TenantBucketResolver.fixed(bucket), maxObjectBytes);
        this.clock = clock;
    }

    /** Falla al arrancar, antes de construir el objeto, si el bucket no admite retencion por objeto. */
    private static GcsBlobApi requireWorm(GcsBlobApi api, String bucket) {
        boolean enabled;
        try {
            enabled = api.objectRetentionEnabled(bucket);
        } catch (GcsBlobApi.BlobApiException e) {
            throw new IllegalStateException("No se pudo verificar la retencion por objeto del bucket WORM '"
                + bucket + "'", e);
        }
        if (!enabled) {
            throw new IllegalStateException("El bucket WORM '" + bucket
                + "' no existe o no tiene habilitada la retencion por objeto");
        }
        return api;
    }

    @Override
    public void putWithRetention(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata,
                                 Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new StorageException("La retencion debe ser positiva");
        }
        putInternal(tenantId, path, data, metadata, clock.instant().plus(retention));
    }

    @Override
    public void applyLegalHold(TenantId tenantId, String path) {
        setHold(tenantId, path, true);
    }

    @Override
    public void removeLegalHold(TenantId tenantId, String path) {
        setHold(tenantId, path, false);
    }

    private void setHold(TenantId tenantId, String path, boolean hold) {
        String key = StorageKeys.key(tenantId, path);
        try {
            api.setTemporaryHold(bucket(tenantId), key, hold);
        } catch (GcsBlobApi.BlobNotFoundException e) {
            throw new StorageException("Objeto no encontrado");
        } catch (GcsBlobApi.BlobApiException e) {
            throw new StorageException("No se pudo cambiar el legal hold: " + e.getMessage());
        }
    }
}
