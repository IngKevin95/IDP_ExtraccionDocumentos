package com.idp.storage.gcs;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.storage.contract.ObjectStoreContract;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AC-04..AC-06 contra fake-gcs-server real: CRUD, aislamiento por tenant y validacion de rutas. */
@Testcontainers(disabledWithoutDocker = true)
class GcsObjectStoreIntegrationTest extends ObjectStoreContract {

    private static final String BUCKET = "idp-it";

    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>("fsouza/fake-gcs-server:1.56.1")
        .withCommand("-scheme", "http", "-port", "4443")
        .withExposedPorts(4443)
        .waitingFor(Wait.forHttp("/storage/v1/b").forPort(4443));

    private static Storage storage;
    private ObjectStore store;

    private static StorageOptions.Builder options(String host) {
        return StorageOptions.newBuilder().setProjectId("idp-test").setHost(host)
            .setCredentials(NoCredentials.getInstance());
    }

    @BeforeAll
    static void bucket() {
        storage = options("http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443)).build().getService();
        storage.create(BucketInfo.of(BUCKET));
    }

    @Override
    protected ObjectStore getStore() {
        if (store == null) {
            store = new GcsObjectStore(new SdkGcsBlobApi(storage), TenantBucketResolver.fixed(BUCKET));
        }
        return store;
    }

    @Override
    protected TenantId getTenantA() {
        return new TenantId("t1");
    }

    @Override
    protected TenantId getTenantB() {
        return new TenantId("t2");
    }

    /** Sustituye el almacen por uno que apunta a un puerto sin servicio y sin reintentos (proveedor caido). */
    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> {
            Storage dead = options("http://localhost:1").setRetrySettings(ServiceOptions.getNoRetrySettings())
                .build().getService();
            store = new GcsObjectStore(new SdkGcsBlobApi(dead), TenantBucketResolver.fixed(BUCKET));
        });
    }

    @Test
    void shaDeclaradoQueNoCoincideSeRechazaSinSubir() throws Exception {
        byte[] data = "contenido".getBytes();
        ObjectMetadata otroHash = new ObjectMetadata("00".repeat(32), data.length, "text/plain");
        assertThrows(StorageException.class,
            () -> getStore().put(getTenantA(), "docs/hash.txt", new ByteArrayInputStream(data), otroHash));
        assertThrows(StorageException.class, () -> getStore().get(getTenantA(), "docs/hash.txt"));
    }
}
