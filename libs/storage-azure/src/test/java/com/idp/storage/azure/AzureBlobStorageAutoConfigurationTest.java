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

    /** Sin el BlobApi falso: ejercita las ramas de credenciales del bean real (no abre conexiones al construir). */
    private ApplicationContextRunner real() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class,
                AzureBlobStorageAutoConfiguration.class))
            .withBean(TenantBucketResolver.class, () -> TenantBucketResolver.fixed("silo"))
            .withPropertyValues("idp.storage.provider=azure-blob");
    }

    private static final String CONNECTION_STRING = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
        + "AccountKey=" + java.util.Base64.getEncoder().encodeToString(new byte[32])
        + ";BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;";

    @Test
    void connectionStringSinEmulatorFallaElArranque() {
        real().withPropertyValues("idp.storage.azure.connection-string=" + CONNECTION_STRING)
            .run(ctx -> assertThat(ctx).getFailure()
                .hasStackTraceContaining("idp.storage.azure.emulator=true")
                .hasStackTraceContaining("account-url"));
    }

    @Test
    void connectionStringConEmulatorCreaElBlobApiReal() {
        real().withPropertyValues("idp.storage.azure.connection-string=" + CONNECTION_STRING,
                "idp.storage.azure.emulator=true")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(BlobApi.class)).isInstanceOf(SdkBlobApi.class);
            });
    }

    @Test
    void accountUrlUsaCredencialesPorDefecto() {
        real().withPropertyValues("idp.storage.azure.account-url=https://acct.blob.core.windows.net")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(BlobApi.class)).isInstanceOf(SdkBlobApi.class);
            });
    }

    @Test
    void unlockedSinAllowUnlockedFallaElArranque() {
        real().withPropertyValues("idp.storage.azure.account-url=https://acct.blob.core.windows.net",
                "idp.storage.azure.immutability-mode=unlocked")
            .run(ctx -> assertThat(ctx).getFailure()
                .hasStackTraceContaining("idp.storage.azure.allow-unlocked=true"));
    }

    @Test
    void unlockedConAllowUnlockedArranca() {
        real().withPropertyValues("idp.storage.azure.account-url=https://acct.blob.core.windows.net",
                "idp.storage.azure.immutability-mode=unlocked", "idp.storage.azure.allow-unlocked=true")
            .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void modoDeInmutabilidadSeInterpretaEstrictamente() {
        assertThat(AzureBlobStorageAutoConfiguration.mode("LOCKED", false))
            .isEqualTo(com.azure.storage.blob.models.BlobImmutabilityPolicyMode.LOCKED);
        assertThat(AzureBlobStorageAutoConfiguration.mode("unlocked", true))
            .isEqualTo(com.azure.storage.blob.models.BlobImmutabilityPolicyMode.UNLOCKED);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> AzureBlobStorageAutoConfiguration.mode("unlocked", false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> AzureBlobStorageAutoConfiguration.mode("mutable", true));
    }

    @Test
    void topeDeObjetoFueraDeRangoFallaElArranque() {
        real().withPropertyValues("idp.storage.azure.account-url=https://acct.blob.core.windows.net",
                "idp.storage.max-object-bytes=0")
            .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("idp.storage.max-object-bytes"));
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
