package com.idp.storage;

import com.idp.tenant.context.TenantBucketResolver;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import software.amazon.awssdk.services.s3.S3Client;

/** Selecciona el {@link ObjectStore} por {@code idp.storage.provider} (ADR 0032). */
@AutoConfiguration
public class StorageAutoConfiguration {

    static final List<String> PROVEEDORES = List.of("s3", "gcs", "azure-blob");
    /** Clase marcadora de cada adaptador que vive en su propio modulo; un proveedor esta implementado si esta en el classpath. */
    private static final Map<String, String> ADAPTADORES = Map.of(
        "gcs", "com.idp.storage.gcs.GcsObjectStore",
        "azure-blob", "com.idp.storage.azure.AzureBlobObjectStore");

    private static List<String> implementados() {
        return PROVEEDORES.stream()
            .filter(p -> !ADAPTADORES.containsKey(p) || ClassUtils.isPresent(ADAPTADORES.get(p), null))
            .toList();
    }

    /** Falla el arranque (AC-02) antes de crear beans, con un mensaje que lista los valores validos. */
    @Bean
    static BeanFactoryPostProcessor storageProviderValidator(Environment env) {
        return beanFactory -> validar(env.getProperty("idp.storage.provider", ""),
            !env.getProperty("idp.control-db.url", "").isBlank());
    }

    static void validar(String provider, boolean controlDbDefinida) {
        if (provider.isBlank()) {
            if (controlDbDefinida) {
                throw new IllegalStateException("idp.storage.provider es obligatorio cuando idp.control-db.url esta"
                    + " definido. Valores validos: " + String.join("|", PROVEEDORES));
            }
            return;
        }
        if (!PROVEEDORES.contains(provider)) {
            throw new IllegalStateException("idp.storage.provider desconocido: '" + provider + "'."
                + " Valores validos: " + String.join("|", PROVEEDORES));
        }
        if (!implementados().contains(provider)) {
            throw new IllegalStateException("idp.storage.provider='" + provider + "': proveedor no implementado"
                + " todavia. Implementados: " + String.join("|", implementados()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idp.storage.provider", havingValue = "s3")
    static class S3Configuration {

        /**
         * Bucket WORM unico (Object Lock) de los servicios que anclan evidencia. Es tambien su ObjectStore, por eso
         * se declara antes que el ObjectStore por tenant, que cede ante el.
         */
        @Bean
        @ConditionalOnProperty(name = "idp.storage.immutable", havingValue = "true")
        @ConditionalOnMissingBean(ObjectStore.class)
        ImmutableStore s3ImmutableStore(@Value("${idp.storage.endpoint:}") String endpoint,
                                        @Value("${idp.storage.region:us-east-1}") String region,
                                        @Value("${idp.storage.access-key:}") String accessKey,
                                        @Value("${idp.storage.secret-key:}") String secretKey,
                                        @Value("${idp.storage.path-style:false}") boolean pathStyle,
                                        @Value("${idp.storage.bucket}") String bucket) {
            if (bucket.isBlank()) {
                throw new IllegalStateException("idp.storage.bucket es obligatorio con idp.storage.immutable=true");
            }
            return new S3ImmutableStore(s3Client(endpoint, region, accessKey, secretKey, pathStyle), bucket);
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStore.class)
        ObjectStore s3ObjectStore(@Value("${idp.storage.endpoint:}") String endpoint,
                                  @Value("${idp.storage.region:us-east-1}") String region,
                                  @Value("${idp.storage.access-key:}") String accessKey,
                                  @Value("${idp.storage.secret-key:}") String secretKey,
                                  @Value("${idp.storage.path-style:false}") boolean pathStyle,
                                  TenantBucketResolver buckets) {
            return new S3ObjectStore(s3Client(endpoint, region, accessKey, secretKey, pathStyle), buckets);
        }

        private static S3Client s3Client(String endpoint, String region, String accessKey, String secretKey,
                                         boolean pathStyle) {
            return S3Clients.create(endpoint.isBlank() ? null : URI.create(endpoint), region,
                accessKey.isBlank() ? null : accessKey, secretKey, pathStyle);
        }
    }
}
