package com.idp.kms.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.models.CreateEcKeyOptions;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.contract.KeyServiceContract;
import com.idp.tenant.TenantId;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/**
 * KeyServiceContract contra Lowkey Vault real (wrapKey/unwrapKey RSA-OAEP-256 con RSA de 3072 y firma ES256 reales,
 * a traves del SDK de Azure). Lowkey Vault exige que el puerto del host coincida con el del vault, asi que se elige
 * un puerto libre y se publica en el mismo numero. Certificado autofirmado: el cliente HTTP de prueba no lo valida.
 */
@Testcontainers(disabledWithoutDocker = true)
class LowkeyVaultContractTest extends KeyServiceContract {

    private static GenericContainer<?> lowkey;
    private static KeyClient admin;
    private static AzureKeyVaultKeyService kms;

    @BeforeAll
    static void arrancar() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        lowkey = new GenericContainer<>("nagyesta/lowkey-vault:7.3.112")
            .withEnv("LOWKEY_ARGS", "--server.port=" + port)
            .withExposedPorts(port)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(port))))
            .waitingFor(Wait.forLogMessage(".*Started LowkeyVaultApp.*", 1));
        lowkey.start();

        String url = "https://localhost:" + port;
        HttpClient http = new NettyAsyncHttpClientBuilder(reactor.netty.http.client.HttpClient.create()
            .secure(ssl -> {
                try {
                    ssl.sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build());
                } catch (javax.net.ssl.SSLException e) {
                    throw new IllegalStateException(e);
                }
            })).build();
        TokenCredential cred = request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1)));
        admin = new KeyClientBuilder().vaultUrl(url).credential(cred).httpClient(http)
            .disableChallengeResourceVerification().buildClient();
        kms = new AzureKeyVaultKeyService(SdkKeyVaultApi.create(url, cred, http, true));
    }

    @AfterAll
    static void parar() {
        if (lowkey != null) {
            lowkey.stop();
        }
    }

    @Override
    protected KeyService getKms() {
        return kms;
    }

    @Override
    protected TenantId getTenantA() {
        return new TenantId("t1");
    }

    @Override
    protected TenantId getTenantB() {
        return new TenantId("t2");
    }

    @Override
    protected void createKeyIfNeeded(TenantId tenant, String keyId) {
        String name = KeyNames.hashed(tenant, keyId);
        try {
            admin.getKey(name);
            return;
        } catch (RuntimeException noExiste) {
            // se crea abajo
        }
        if (SIGNING_KEY.equals(keyId)) {
            admin.createEcKey(new CreateEcKeyOptions(name).setCurveName(KeyCurveName.P_256)
                .setKeyOperations(KeyOperation.SIGN, KeyOperation.VERIFY));
        } else {
            admin.createRsaKey(new CreateRsaKeyOptions(name).setKeySize(3072)
                // Operaciones explicitas: Lowkey Vault no asigna las de firma por defecto y pide ENCRYPT/DECRYPT para wrapKey.
                .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY, KeyOperation.ENCRYPT,
                    KeyOperation.DECRYPT));
        }
    }

    @Test
    void llaveInexistenteEs404YSeReportaComoKeyNotFound() {
        org.junit.jupiter.api.Assertions.assertThrows(KeyService.KeyNotFoundException.class,
            () -> kms.wrapDek(getTenantA(), new byte[32], "sin-provisionar", Map.of()));
    }

    @Test
    void firmaEs256DeLowkeyEsRsCrudaDe64Bytes() {
        createKeyIfNeeded(getTenantA(), SIGNING_KEY);
        byte[] sig = kms.sign(getTenantA(), "export".getBytes(StandardCharsets.UTF_8), SIGNING_KEY).getData();
        assertThat(sig).hasSize(64);
    }
}
