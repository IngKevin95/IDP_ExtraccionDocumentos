package com.idp.storage.gcs;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.StorageOptions;
import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectStore;
import com.idp.storage.StorageAutoConfiguration;
import com.idp.tenant.context.TenantBucketResolver;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Cableado del proveedor {@code idp.storage.provider=gcs} (ADR 0032). Credenciales por defecto de la aplicacion
 * (ADC / Workload Identity). {@code idp.storage.gcs.endpoint} solo cambia el host; sin credenciales unicamente con
 * {@code idp.storage.gcs.emulator=true} explicito.
 */
@AutoConfiguration(after = StorageAutoConfiguration.class)
@ConditionalOnProperty(name = "idp.storage.provider", havingValue = "gcs")
public class GcsStorageAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    GcsBlobApi gcsBlobApi(@Value("${idp.storage.gcs.project-id:}") String projectId,
                          @Value("${idp.storage.gcs.endpoint:}") String endpoint,
                          @Value("${idp.storage.gcs.emulator:false}") boolean emulator) {
        return new SdkGcsBlobApi(storageOptions(projectId, endpoint, emulator).getService());
    }

    static StorageOptions storageOptions(String projectId, String endpoint, boolean emulator) {
        StorageOptions.Builder options = StorageOptions.newBuilder();
        if (!projectId.isBlank()) {
            options.setProjectId(projectId);
        }
        if (!endpoint.isBlank()) {
            options.setHost(endpoint);
        }
        if (emulator) {
            options.setCredentials(NoCredentials.getInstance());
        }
        return options.build();
    }

    /** Bucket WORM unico de los servicios que anclan evidencia; es tambien su ObjectStore (igual que en S3). */
    @Bean
    @ConditionalOnProperty(name = "idp.storage.immutable", havingValue = "true")
    @ConditionalOnMissingBean(ObjectStore.class)
    ImmutableStore gcsImmutableStore(GcsBlobApi api, @Value("${idp.storage.bucket:}") String bucket,
                                     @Value("${idp.storage.max-object-bytes:268435456}") long maxObjectBytes) {
        if (bucket.isBlank()) {
            throw new IllegalStateException("idp.storage.bucket es obligatorio con idp.storage.immutable=true");
        }
        return new GcsImmutableStore(api, bucket, Clock.systemUTC(), maxObjectBytes);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore gcsObjectStore(GcsBlobApi api, TenantBucketResolver buckets,
                               @Value("${idp.storage.max-object-bytes:268435456}") long maxObjectBytes) {
        return new GcsObjectStore(api, buckets, maxObjectBytes);
    }
}
