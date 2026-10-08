package com.idp.kms.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.EnvelopeCryptoAutoConfiguration;
import com.idp.kms.KeyService;
import com.idp.kms.KmsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Multicloud AC-02: seleccion de Key Vault por propiedad con el modulo en el classpath. */
class AzureKeyVaultKmsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KmsAutoConfiguration.class,
            AzureKeyVaultKmsAutoConfiguration.class, EnvelopeCryptoAutoConfiguration.class));

    @Test
    void azureKeyVaultCreaElAdaptadorYElEnvelopeCrypto() {
        runner.withPropertyValues("idp.kms.provider=azure-keyvault", "idp.control-db.url=jdbc:x",
                "idp.kms.azure.vault-url=https://idp-test.vault.azure.net")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(KeyService.class)).hasSize(1);
                assertThat(ctx.getBean(KeyService.class)).isInstanceOf(AzureKeyVaultKeyService.class);
                assertThat(ctx).hasSingleBean(EnvelopeCrypto.class);
            });
    }

    @Test
    void conElModuloEnElClasspathElValidadorYaNoDiceNoImplementado() {
        runner.withPropertyValues("idp.kms.provider=azure-keyvault")
            .run(ctx -> assertThat(ctx).getFailure().rootCause()
                .hasMessageContaining("idp.kms.azure.vault-url")
                .hasMessageNotContaining("no implementado"));
    }

    @Test
    void faltaVaultUrlFallaConMensajeClaro() {
        runner.withPropertyValues("idp.kms.provider=azure-keyvault", "idp.kms.azure.vault-url= ")
            .run(ctx -> assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessage("idp.kms.azure.vault-url es obligatorio con idp.kms.provider=azure-keyvault"));
    }

    @Test
    void httpSoloConEmuladorExplicito() {
        runner.withPropertyValues("idp.kms.provider=azure-keyvault", "idp.kms.azure.vault-url=http://localhost:8080")
            .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("https"));
        runner.withPropertyValues("idp.kms.provider=azure-keyvault", "idp.kms.azure.vault-url=http://localhost:8080",
                "idp.kms.azure.emulator=true")
            .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void otroProveedorNoCreaElAdaptadorAzure() {
        runner.withPropertyValues("idp.kms.provider=openbao", "idp.openbao.address=https://openbao:8200",
                "idp.openbao.token=t")
            .run(ctx -> assertThat(ctx.getBean(KeyService.class)).isNotInstanceOf(AzureKeyVaultKeyService.class));
    }
}
