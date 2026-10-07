package com.idp.storage.gcs;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.StorageOptions;
import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectStore;
import com.idp.storage.StorageAutoConfiguration;
import com.idp.tenant.context.TenantBucketResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Cableado del proveedor {@code idp.storage.provider=gcs} (ADR 0032). Credenciales por defecto de la aplicacion
 * (ADC / Workload Identity); con {@code idp.storage.gcs.endpoint} (emulador) no se usan credenciales.
 */
@AutoConfiguration(after = StorageAutoConfiguration.class)
@ConditionalOnProperty(name = "idp.storage.provider", havingValue = "gcs")
public class GcsStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GcsBlobApi gcsBlobApi(@Value("${idp.storage.gcs.project-id:}") String projectId,
                          @Value("${idp.storage.gcs.endpoint:}") String endpoint) {
        StorageOptions.Builder options = StorageOptions.newBuilder();
        if (!projectId.isBlank()) {
            options.setProjectId(projectId);
        }
        if (!endpoint.isBlank()) {
            options.setHost(endpoint).setCredentials(NoCredentials.getInstance());
        }
        return new SdkGcsBlobApi(options.build().getService());
    }

    /** Bucket WORM unico de los servicios que anclan evidencia; es tambien su ObjectStore (igual que en S3). */
    @Bean
    @ConditionalOnProperty(name = "idp.storage.immutable", havingValue = "true")
    @ConditionalOnMissingBean(ObjectStore.class)
    ImmutableStore gcsImmutableStore(GcsBlobApi api, @Value("${idp.storage.bucket:}") String bucket) {
        if (bucket.isBlank()) {
            throw new IllegalStateException("idp.storage.bucket es obligatorio con idp.storage.immutable=true");
        }
        return new GcsImmutableStore(api, bucket);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore gcsObjectStore(GcsBlobApi api, TenantBucketResolver buckets) {
        return new GcsObjectStore(api, buckets);
    }
}
