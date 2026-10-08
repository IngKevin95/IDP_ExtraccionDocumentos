package com.idp.kms.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.EnvelopeCryptoAutoConfiguration;
import com.idp.kms.KeyService;
import com.idp.kms.KmsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.kms.KmsClient;

class AwsKmsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KmsAutoConfiguration.class, AwsKmsAutoConfiguration.class,
            EnvelopeCryptoAutoConfiguration.class))
        .withPropertyValues("idp.kms.aws.region=us-east-1");

    @Test
    void awsKmsCreaElKeyServiceYElEnvelopeCrypto() {
        runner.withPropertyValues("idp.kms.provider=aws-kms", "idp.control-db.url=jdbc:x").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(KeyService.class)).isInstanceOf(AwsKmsKeyService.class);
            assertThat(ctx).hasSingleBean(EnvelopeCrypto.class);
            assertThat(ctx).hasSingleBean(KmsClient.class);
        });
    }

    @Test
    void sinElProveedorAwsNoSeCreaNada() {
        runner.withPropertyValues("idp.kms.provider=openbao", "idp.openbao.address=https://x:8200",
            "idp.openbao.token=t").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(KmsClient.class);
                assertThat(ctx.getBean(KeyService.class)).isNotInstanceOf(AwsKmsKeyService.class);
            });
    }

    @Test
    void emulatorSinClavesFalla() {
        runner.withPropertyValues("idp.kms.provider=aws-kms", "idp.kms.aws.emulator=true",
                "idp.kms.aws.endpoint=http://localhost:4566")
            .run(ctx -> assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idp.kms.aws.access-key"));
    }

    @Test
    void emulatorConClavesArranca() {
        runner.withPropertyValues("idp.kms.provider=aws-kms", "idp.kms.aws.emulator=true",
                "idp.kms.aws.endpoint=http://localhost:4566", "idp.kms.aws.access-key=a", "idp.kms.aws.secret-key=b")
            .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(KeyService.class));
    }

    @Test
    void endpointSinEmulatorMantieneLaCadenaPorDefecto() {
        assertThat(AwsKmsAutoConfiguration.staticCredentials(false, "a", "b")).isEmpty();
        assertThat(AwsKmsAutoConfiguration.staticCredentials(true, "a", "b")).isPresent();
        runner.withPropertyValues("idp.kms.provider=aws-kms", "idp.kms.aws.endpoint=http://localhost:4566",
                "idp.kms.aws.access-key=a", "idp.kms.aws.secret-key=b")
            .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(KeyService.class));
    }

    @Test
    void elClienteSeCierraAlApagarElContexto() {
        KmsClient[] cliente = new KmsClient[1];
        runner.withPropertyValues("idp.kms.provider=aws-kms").run(ctx -> cliente[0] = ctx.getBean(KmsClient.class));
        // run() ya cerro el contexto; el cliente cerrado falla por el pool HTTP apagado, no por red ni credenciales.
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> cliente[0].listKeys());
        assertThat(e).hasMessageContaining("Connection pool shut down");
    }
}
