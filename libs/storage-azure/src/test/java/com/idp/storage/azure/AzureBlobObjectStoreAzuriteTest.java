package com.idp.storage.azure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobImmutabilityPolicyMode;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.storage.contract.ObjectStoreContract;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** ObjectStoreContract (AC-04..AC-06) y verificacion de SHA-256 contra Azurite real. */
@Testcontainers(disabledWithoutDocker = true)
class AzureBlobObjectStoreAzuriteTest extends ObjectStoreContract {

    private static final String CONTAINER = "idp-it";
    // Cuenta y llave de desarrollo publicadas en la documentacion de Azurite; no son un secreto.
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Container
    static final GenericContainer<?> AZURITE =
        new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:3.37.0"))
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
            .withExposedPorts(10000);

    private static BlobServiceClient service;
    private final TenantId tenantA = new TenantId("t1");
    private final TenantId tenantB = new TenantId("t2");
    private ObjectStore store;

    private static String connectionString(int port) {
        return "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT + ";AccountKey=" + KEY
            + ";BlobEndpoint=http://" + AZURITE.getHost() + ":" + port + "/" + ACCOUNT + ";";
    }

    @BeforeAll
    static void container() {
        service = new BlobServiceClientBuilder()
            .connectionString(connectionString(AZURITE.getMappedPort(10000))).buildClient();
        service.createBlobContainerIfNotExists(CONTAINER);
    }

    private static ObjectStore storeOn(BlobServiceClient client) {
        return new AzureBlobObjectStore(new SdkBlobApi(client, BlobImmutabilityPolicyMode.UNLOCKED, 256L * 1024 * 1024),
            TenantBucketResolver.fixed(CONTAINER));
    }

    @Override
    protected ObjectStore getStore() {
        if (store == null) {
            store = storeOn(service);
        }
        return store;
    }

    @Override
    protected TenantId getTenantA() {
        return tenantA;
    }

    @Override
    protected TenantId getTenantB() {
        return tenantB;
    }

    /** Apunta el store a un puerto cerrado: falla la conexion, sin reintentos largos. */
    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> store = storeOn(new BlobServiceClientBuilder()
            .connectionString(connectionString(1))
            .retryOptions(new RequestRetryOptions(RetryPolicyType.FIXED, 1, 5, 10L, 10L, null))
            .buildClient()));
    }

    @Test
    void shaDistintoDelContenidoNoDejaElBlobVisible() throws Exception {
        byte[] data = "contenido".getBytes();
        String otroHash = "00".repeat(32);
        assertThrows(StorageException.class, () -> getStore().put(tenantA, "docs/sha.txt",
            new ByteArrayInputStream(data), new ObjectMetadata(otroHash, data.length, "text/plain")));
        assertThrows(StorageException.class, () -> getStore().get(tenantA, "docs/sha.txt"));
    }

    @Test
    void objetoDeVariosBloquesHaceRoundTrip() throws Exception {
        byte[] data = new byte[SdkBlobApi.BLOCK_SIZE * 2 + 123];
        new Random(7).nextBytes(data);
        getStore().put(tenantA, "docs/grande.bin", new ByteArrayInputStream(data), meta(data));
        try (var in = getStore().get(tenantA, "docs/grande.bin")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void objetoVacioHaceRoundTrip() throws Exception {
        byte[] data = new byte[0];
        getStore().put(tenantA, "docs/vacio.bin", new ByteArrayInputStream(data), meta(data));
        try (var in = getStore().get(tenantA, "docs/vacio.bin")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }
}
