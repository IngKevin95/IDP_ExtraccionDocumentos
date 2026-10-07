package com.idp.storage.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectStore;
import com.idp.storage.StorageAutoConfiguration;
import com.idp.tenant.context.TenantBucketResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Multicloud AC-01/AC-09: seleccion del adaptador Azure Blob y arranque fallido sin WORM por version. */
class AzureBlobStorageAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class Infra {
        @Bean
        TenantBucketResolver buckets() {
            return TenantBucketResolver.fixed("silo");
        }

        @Bean
        BlobApi blobApi() {
            InMemoryBlobApi api = new InMemoryBlobApi(new MutableClock());
            api.createContainer("worm", true);
            api.createContainer("sin-worm", false);
            return api;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class,
            AzureBlobStorageAutoConfiguration.class))
        .withUserConfiguration(Infra.class);

    @Test
    void azureBlobCreaSoloUnObjectStoreAzure() {
        runner.withPropertyValues("idp.storage.provider=azure-blob", "idp.control-db.url=jdbc:x")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(ObjectStore.class)).hasSize(1);
                assertThat(ctx.getBean(ObjectStore.class)).isInstanceOf(AzureBlobObjectStore.class)
                    .isNotInstanceOf(ImmutableStore.class);
            });
    }

    @Test
    void azureBlobConInmutableCreaUnSoloBeanWormQueEsTambienElObjectStore() {
        runner.withPropertyValues("idp.storage.provider=azure-blob", "idp.storage.immutable=true",
                "idp.storage.bucket=worm")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(ObjectStore.class)).hasSize(1);
                assertThat(ctx.getBean(ImmutableStore.class)).isInstanceOf(AzureBlobImmutableStore.class);
            });
    }

    @Test
    void contenedorWormSinVersionadoHaceFallarElArranque() {
        runner.withPropertyValues("idp.storage.provider=azure-blob", "idp.storage.immutable=true",
                "idp.storage.bucket=sin-worm")
            .run(ctx -> assertThat(ctx).getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("immutable storage con versionado"));
    }

    @Test
    void conElModuloEnElClasspathElValidadorYaNoDiceNoImplementado() {
        runner.withPropertyValues("idp.storage.provider=azure-blob")
            .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void sinCredencialesNiEndpointFallaConMensajeClaro() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class,
                AzureBlobStorageAutoConfiguration.class))
            .withBean(TenantBucketResolver.class, () -> TenantBucketResolver.fixed("silo"))
            .withPropertyValues("idp.storage.provider=azure-blob")
            .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("idp.storage.azure.account-url"));
    }

    @Test
    void sinProveedorAzureNoSeCreaNada() {
        runner.withPropertyValues("idp.storage.provider=s3", "idp.storage.endpoint=http://localhost:9",
                "idp.storage.access-key=k", "idp.storage.secret-key=s")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(AzureBlobObjectStore.class);
            });
    }
}
