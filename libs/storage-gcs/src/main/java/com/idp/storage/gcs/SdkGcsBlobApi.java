package com.idp.storage.gcs;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/** {@link GcsBlobApi} sobre el cliente oficial {@code com.google.cloud.storage.Storage}. */
public class SdkGcsBlobApi implements GcsBlobApi, AutoCloseable {

    private final Storage storage;

    public SdkGcsBlobApi(Storage storage) {
        this.storage = storage;
    }

    @Override
    public void write(String bucket, String key, byte[] data, String contentType, Instant retainUntil) {
        BlobInfo.Builder info = BlobInfo.newBuilder(BlobId.of(bucket, key));
        if (contentType != null) {
            info.setContentType(contentType);
        }
        if (retainUntil != null) {
            info.setRetention(BlobInfo.Retention.newBuilder().setMode(BlobInfo.Retention.Mode.LOCKED)
                .setRetainUntilTime(OffsetDateTime.ofInstant(retainUntil, ZoneOffset.UTC)).build());
        }
        call(() -> storage.create(info.build(), data));
    }

    @Override
    public byte[] read(String bucket, String key) {
        return call(() -> storage.readAllBytes(BlobId.of(bucket, key)));
    }

    @Override
    public void delete(String bucket, String key) {
        call(() -> storage.delete(BlobId.of(bucket, key)));
    }

    @Override
    public void setTemporaryHold(String bucket, String key, boolean hold) {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, key)).setTemporaryHold(hold).build();
        call(() -> storage.update(info));
    }

    @Override
    public boolean objectRetentionEnabled(String bucket) {
        Bucket found = call(() -> storage.get(bucket));
        BucketInfo.ObjectRetention retention = found == null ? null : found.getObjectRetention();
        return retention != null && BucketInfo.ObjectRetention.Mode.ENABLED.equals(retention.getMode());
    }

    /** Libera los recursos del cliente (conexiones, hilos) al apagar el contexto. */
    @Override
    public void close() {
        try {
            storage.close();
        } catch (Exception e) {
            throw new BlobApiException(e.getClass().getSimpleName());
        }
    }

    /** Traduce los errores del SDK a excepciones propias sin el mensaje original (puede traer la ruta del objeto). */
    private static <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (StorageException e) {
            if (e.getCode() == 404) {
                throw new BlobNotFoundException();
            }
            throw new BlobApiException("error del proveedor (codigo " + e.getCode() + ")");
        } catch (RuntimeException e) {
            throw new BlobApiException(e.getClass().getSimpleName());
        }
    }
}
