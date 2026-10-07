package com.idp.kms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Multicloud AC-02: seleccion de KeyService por propiedad y arranque fallido sin proveedor valido. */
class KmsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KmsAutoConfiguration.class, EnvelopeCryptoAutoConfiguration.class));

    @Test
    void openbaoCreaSoloElAdaptadorOpenBaoYElEnvelopeCrypto() {
        runner.withPropertyValues("idp.kms.provider=openbao", "idp.openbao.address=https://openbao:8200",
                "idp.openbao.token=t", "idp.control-db.url=jdbc:x")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(KeyService.class)).hasSize(1);
                assertThat(ctx.getBean(KeyService.class)).isInstanceOf(OpenBaoTransitKeyService.class);
                assertThat(ctx).hasSingleBean(EnvelopeCrypto.class);
            });
    }

    @Test
    void openbaoSinDireccionFalla() {
        runner.withPropertyValues("idp.kms.provider=openbao", "idp.openbao.token=t")
            .run(ctx -> assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idp.openbao.address"));
    }

    @Test
    void sinProveedorConBaseDeControlFallaListandoLosValoresValidos() {
        runner.withPropertyValues("idp.control-db.url=jdbc:x")
            .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idp.kms.provider es obligatorio")
                .hasMessageContaining("openbao|aws-kms|gcp-kms|azure-keyvault"));
    }

    @Test
    void devModeNoExcusaLaFaltaDeProveedorConBaseDeControl() {
        runner.withPropertyValues("idp.control-db.url=jdbc:x", "idp.security.dev-mode=true")
            .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idp.kms.provider es obligatorio"));
    }

    @Test
    void proveedorDesconocidoFalla() {
        runner.withPropertyValues("idp.kms.provider=thales")
            .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("desconocido: 'thales'")
                .hasMessageContaining("openbao|aws-kms|gcp-kms|azure-keyvault"));
    }

    @Test
    void proveedoresSinAdaptadorFallanComoNoImplementados() {
        for (String provider : new String[] {"aws-kms", "gcp-kms", "azure-keyvault"}) {
            runner.withPropertyValues("idp.kms.provider=" + provider)
                .run(ctx -> assertThat(ctx).getFailure().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("proveedor no implementado todavia"));
        }
    }

    @Test
    void devModeSinProveedorCaeAEnMemoria() {
        runner.withPropertyValues("idp.security.dev-mode=true")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(KeyService.class)).isInstanceOf(InMemoryKeyService.class);
                assertThat(ctx).hasSingleBean(EnvelopeCrypto.class);
            });
    }

    @Test
    void fueraDeDevModeNuncaCaeAEnMemoria() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(KeyService.class);
            assertThat(ctx).doesNotHaveBean(EnvelopeCrypto.class);
        });
    }
}
