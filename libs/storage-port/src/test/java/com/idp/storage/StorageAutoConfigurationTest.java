package com.idp.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.tenant.context.TenantBucketResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Multicloud AC-01/AC-02: seleccion de ObjectStore por propiedad y arranque fallido sin proveedor valido. */
class StorageAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class Resolver {
        @Bean
        TenantBucketResolver buckets() {
            return TenantBucketResolver.fixed("bucket");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class))
        .withUserConfiguration(Resolver.class)
        .withPropertyValues("idp.storage.endpoint=http://localhost:9", "idp.storage.access-key=k",
            "idp.storage.secret-key=s");

    @Test
    void s3CreaSoloUnObjectStoreS3() {
        runner.withPropertyValues("idp.storage.provider=s3", "idp.control-db.url=jdbc:x")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(ObjectStore.class)).hasSize(1);
                assertThat(ctx.getBean(ObjectStore.class)).isInstanceOf(S3ObjectStore.class)
                    .isNotInstanceOf(ImmutableStore.class);
                assertThat(ctx).doesNotHaveBean(ImmutableStore.class);
            });
    }

    @Test
    void s3ConInmutableCreaUnSoloBeanWormQueEsTambienElObjectStore() {
        runner.withPropertyValues("idp.storage.provider=s3", "idp.storage.immutable=true", "idp.storage.bucket=worm")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(ObjectStore.class)).hasSize(1);
                assertThat(ctx.getBean(ImmutableStore.class)).isInstanceOf(S3ImmutableStore.class);
            });
    }

    @Test
    void sinProveedorYSinBaseDeControlNoCreaStore() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ObjectStore.class);
        });
    }

    @Test
    void sinProveedorConBaseDeControlFallaListandoLosValoresValidos() {
        runner.withPropertyValues("idp.control-db.url=jdbc:x")
            .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idp.storage.provider es obligatorio")
                .hasMessageContaining("s3|gcs|azure-blob"));
    }

    @Test
    void proveedorDesconocidoFalla() {
        runner.withPropertyValues("idp.storage.provider=minio")
            .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("desconocido: 'minio'")
                .hasMessageContaining("s3|gcs|azure-blob"));
    }

    @Test
    void proveedoresSinAdaptadorFallanComoNoImplementados() {
        for (String provider : new String[] {"gcs", "azure-blob"}) {
            runner.withPropertyValues("idp.storage.provider=" + provider)
                .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("proveedor no implementado todavia"));
        }
    }
}
