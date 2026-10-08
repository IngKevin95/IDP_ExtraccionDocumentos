package com.idp.kms.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.EnvelopeCryptoAutoConfiguration;
import com.idp.kms.KeyService;
import com.idp.kms.KmsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GcpKmsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KmsAutoConfiguration.class, GcpKmsAutoConfiguration.class,
            EnvelopeCryptoAutoConfiguration.class));

    private static final String[] COMPLETAS = {"idp.kms.provider=gcp-kms", "idp.kms.gcp.project-id=p",
        "idp.kms.gcp.location=l", "idp.kms.gcp.key-ring=r", "idp.kms.gcp.emulator=true",
        "idp.kms.gcp.endpoint=localhost:9", "idp.control-db.url=jdbc:x"};

    @Test
    void gcpKmsCreaElAdaptadorYElEnvelopeCryptoYElValidadorYaNoDiceNoImplementado() {
        runner.withPropertyValues(COMPLETAS).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(KeyService.class)).hasSize(1);
            assertThat(ctx.getBean(KeyService.class)).isInstanceOf(GcpKmsKeyService.class);
            assertThat(ctx).hasSingleBean(EnvelopeCrypto.class);
        });
    }

    @Test
    void elClienteSeCierraAlApagarElContexto() {
        KeyManagementServiceClient[] cliente = new KeyManagementServiceClient[1];
        runner.withPropertyValues(COMPLETAS).run(ctx -> {
            cliente[0] = ctx.getBean(KeyManagementServiceClient.class);
            assertThat(cliente[0].isShutdown()).isFalse();
        });
        assertThat(cliente[0].isShutdown()).isTrue();
    }

    @Test
    void otroProveedorNoCreaElAdaptadorGcp() {
        runner.withPropertyValues("idp.kms.provider=openbao", "idp.openbao.address=https://openbao:8200",
                "idp.openbao.token=t")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(KeyManagementServiceClient.class));
    }

    @Test
    void faltaUnaPropiedadObligatoriaFallaConMensajeClaro() {
        for (String falta : new String[] {"project-id", "location", "key-ring"}) {
            String[] props = java.util.Arrays.stream(COMPLETAS)
                .filter(p -> !p.startsWith("idp.kms.gcp." + falta + "=")).toArray(String[]::new);
            runner.withPropertyValues(props).run(ctx -> assertThat(ctx).getFailure()
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("idp.kms.gcp." + falta));
        }
    }

    @Test
    void emuladorSinEndpointFalla() {
        String[] props = java.util.Arrays.stream(COMPLETAS)
            .filter(p -> !p.startsWith("idp.kms.gcp.endpoint=")).toArray(String[]::new);
        runner.withPropertyValues(props).run(ctx -> assertThat(ctx).getFailure()
            .hasMessageContaining("idp.kms.gcp.endpoint"));
    }

    @Test
    void soloElEmuladorExplicitoDesactivaLasCredenciales() throws Exception {
        assertThat(GcpKmsAutoConfiguration.settings("localhost:9", true).getCredentialsProvider())
            .isInstanceOf(NoCredentialsProvider.class);
        assertThat(GcpKmsAutoConfiguration.settings("localhost:9", false).getCredentialsProvider())
            .isNotInstanceOf(NoCredentialsProvider.class);
        assertThat(GcpKmsAutoConfiguration.settings("localhost:9", false).getEndpoint()).isEqualTo("localhost:9");
        assertThat(GcpKmsAutoConfiguration.settings("", false).getCredentialsProvider())
            .isNotInstanceOf(NoCredentialsProvider.class);
    }
}
