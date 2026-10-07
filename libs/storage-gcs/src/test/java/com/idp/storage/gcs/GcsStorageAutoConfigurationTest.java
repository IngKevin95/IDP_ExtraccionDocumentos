package com.idp.storage.gcs;

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

/** Multicloud AC-01: idp.storage.provider=gcs crea el bean correcto y el validador ya no dice "no implementado". */
class GcsStorageAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class Collaborators {
        @Bean
        TenantBucketResolver buckets() {
            return TenantBucketResolver.fixed("bucket");
        }

        @Bean
        GcsBlobApi api() {
            return new InMemoryGcsBlobApi().withObjectRetention("worm");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class, GcsStorageAutoConfiguration.class))
        .withUserConfiguration(Collaborators.class)
        .withPropertyValues("idp.storage.provider=gcs");

    @Test
    void gcsCreaSoloUnObjectStoreGcs() {
        runner.withPropertyValues("idp.control-db.url=jdbc:x").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(ObjectStore.class)).hasSize(1);
            assertThat(ctx.getBean(ObjectStore.class)).isInstanceOf(GcsObjectStore.class)
                .isNotInstanceOf(ImmutableStore.class);
        });
    }

    @Test
    void gcsConInmutableCreaUnSoloBeanWormQueEsTambienElObjectStore() {
        runner.withPropertyValues("idp.storage.immutable=true", "idp.storage.bucket=worm").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(ObjectStore.class)).hasSize(1);
            assertThat(ctx.getBean(ImmutableStore.class)).isInstanceOf(GcsImmutableStore.class);
        });
    }

    @Test
    void inmutableConBucketSinRetencionPorObjetoFallaElArranque() {
        runner.withPropertyValues("idp.storage.immutable=true", "idp.storage.bucket=sin-lock")
            .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("retencion por objeto"));
    }

    @Test
    void inmutableSinBucketFalla() {
        runner.withPropertyValues("idp.storage.immutable=true")
            .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("idp.storage.bucket es obligatorio"));
    }

    @Test
    void sinCollaboradorLaAutoconfiguracionCreaElClienteConEndpointDeEmulador() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class,
                GcsStorageAutoConfiguration.class))
            .withBean(TenantBucketResolver.class, () -> TenantBucketResolver.fixed("bucket"))
            .withPropertyValues("idp.storage.provider=gcs", "idp.storage.gcs.project-id=p",
                "idp.storage.gcs.endpoint=http://localhost:9")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(GcsBlobApi.class)).isInstanceOf(SdkGcsBlobApi.class);
                assertThat(ctx.getBean(ObjectStore.class)).isInstanceOf(GcsObjectStore.class);
            });
    }

    @Test
    void otroProveedorNoCreaBeansGcs() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GcsStorageAutoConfiguration.class))
            .withPropertyValues("idp.storage.provider=s3")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(GcsBlobApi.class).doesNotHaveBean(ObjectStore.class));
    }
}
