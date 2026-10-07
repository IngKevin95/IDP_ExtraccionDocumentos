package com.idp.storage.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobImmutabilityPolicyMode;
import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectStore;
import com.idp.storage.StorageAutoConfiguration;
import com.idp.tenant.context.TenantBucketResolver;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Cablea el adaptador Azure Blob cuando {@code idp.storage.provider=azure-blob} (ADR 0032). Credenciales: con
 * {@code idp.storage.azure.connection-string} (Azurite, desarrollo) o, por defecto, {@code DefaultAzureCredential}
 * (Workload Identity en AKS) contra {@code idp.storage.azure.account-url}.
 */
@AutoConfiguration(after = StorageAutoConfiguration.class)
@ConditionalOnProperty(name = "idp.storage.provider", havingValue = "azure-blob")
public class AzureBlobStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BlobApi.class)
    BlobApi azureBlobApi(@Value("${idp.storage.azure.account-url:}") String accountUrl,
                         @Value("${idp.storage.azure.connection-string:}") String connectionString,
                         @Value("${idp.storage.azure.immutability-mode:locked}") String immutabilityMode) {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (!connectionString.isBlank()) {
            builder.connectionString(connectionString);
        } else if (!accountUrl.isBlank()) {
            builder.endpoint(accountUrl).credential(new DefaultAzureCredentialBuilder().build());
        } else {
            throw new IllegalStateException("idp.storage.azure.account-url o idp.storage.azure.connection-string"
                + " es obligatorio con idp.storage.provider=azure-blob");
        }
        BlobServiceClient service = builder.buildClient();
        return new SdkBlobApi(service, mode(immutabilityMode));
    }

    private static BlobImmutabilityPolicyMode mode(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "locked" -> BlobImmutabilityPolicyMode.LOCKED;
            case "unlocked" -> BlobImmutabilityPolicyMode.UNLOCKED;
            default -> throw new IllegalStateException(
                "idp.storage.azure.immutability-mode desconocido: '" + value + "'. Valores validos: locked|unlocked");
        };
    }

    /**
     * Contenedor WORM unico de los servicios que anclan evidencia. Es tambien su ObjectStore, por eso se declara
     * antes que el ObjectStore por tenant, que cede ante el.
     */
    @Bean
    @ConditionalOnProperty(name = "idp.storage.immutable", havingValue = "true")
    @ConditionalOnMissingBean(ObjectStore.class)
    ImmutableStore azureBlobImmutableStore(BlobApi api, @Value("${idp.storage.bucket:}") String container) {
        if (container.isBlank()) {
            throw new IllegalStateException("idp.storage.bucket es obligatorio con idp.storage.immutable=true");
        }
        return AzureBlobImmutableStore.forContainer(api, container, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore azureBlobObjectStore(BlobApi api, TenantBucketResolver containers) {
        return new AzureBlobObjectStore(api, containers);
    }
}
