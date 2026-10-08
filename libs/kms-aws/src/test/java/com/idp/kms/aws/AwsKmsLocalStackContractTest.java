package com.idp.kms.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.kms.KeyService;
import com.idp.kms.contract.KeyServiceContract;
import com.idp.tenant.TenantId;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsException;

/**
 * KeyServiceContract contra LocalStack real. Cubre cifrado, EncryptionContext, aislamiento y disable. La firma Ed25519
 * no esta soportada por LocalStack (ver {@code firmaEd25519NoSoportadaPorLocalStack}) y se prueba con el fake.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsKmsLocalStackContractTest extends KeyServiceContract {

    @Container
    static final LocalStackContainer LOCALSTACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices("kms");

    private static KmsClient client;
    private static AwsKmsKeyService kms;
    private static AwsKmsClientApi api;
    private boolean providerDown;

    private static KmsClient clientFor(URI endpoint) {
        return KmsClient.builder().endpointOverride(endpoint).region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .build();
    }

    @BeforeAll
    static void montar() {
        client = clientFor(LOCALSTACK.getEndpoint());
        api = new AwsKmsClientApi(client);
        kms = new AwsKmsKeyService(client, 7);
    }

    @AfterAll
    static void cerrar() {
        client.close();
    }

    @Override
    protected KeyService getKms() {
        if (providerDown) {
            // Puerto cerrado: el cliente no puede conectar.
            return new AwsKmsKeyService(clientFor(URI.create("http://127.0.0.1:1")), 7);
        }
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
        api.createKey(AwsKmsKeyService.alias(tenant, keyId), SIGNING_KEY.equals(keyId));
    }

    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> providerDown = true);
    }

    @Test
    void createKeyCreaLaLlaveYSuAlias() {
        createKeyIfNeeded(getTenantA(), "alias-check");
        createKeyIfNeeded(getTenantA(), "alias-check"); // idempotente
        String alias = AwsKmsKeyService.alias(getTenantA(), "alias-check");
        long coincidencias = client.listAliases().aliases().stream().filter(a -> a.aliasName().equals(alias)).count();
        assertEquals(1, coincidencias);
        assertTrue(client.describeKey(b -> b.keyId(alias)).keyMetadata().enabled());
    }

    /**
     * LocalStack 3.8 responde 500 al crear llaves ECC_NIST_EDWARDS25519. Si algun dia las soporta, este test falla y
     * avisa de que ya se puede heredar la firma de la suite contra LocalStack.
     */
    @Test
    void firmaEd25519NoSoportadaPorLocalStack() {
        assertThrows(KmsException.class,
            () -> api.createKey(AwsKmsKeyService.alias(getTenantB(), "firma-probe"), true));
    }

    // signYVerifyRoundtrip y signatureAlgorithmConsistenteConPublicKeys no corren aqui: se cubren en
    // AwsKmsFakeContractTest.
    @Override
    @Test
    protected void signYVerifyRoundtrip() {
        Assumptions.abort("LocalStack no soporta ED25519_SHA_512");
    }

    @Override
    @Test
    protected void signatureAlgorithmConsistenteConPublicKeys() {
        Assumptions.abort("LocalStack no soporta ED25519_SHA_512");
    }
}
