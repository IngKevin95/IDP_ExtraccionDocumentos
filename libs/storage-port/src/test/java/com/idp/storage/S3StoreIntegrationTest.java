package com.idp.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idp.storage.contract.ImmutableStoreContract;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectLegalHoldRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;

/** Object Lock real contra LocalStack. */
@Testcontainers(disabledWithoutDocker = true)
class S3StoreIntegrationTest extends ImmutableStoreContract {

    @Container
    static final LocalStackContainer LOCALSTACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices("s3");

    private static S3Client s3;
    private final TenantId tenant = new TenantId("t1");
    private final TenantId tenantB = new TenantId("t2");
    private S3ImmutableStore storeInstance;

    @Override
    protected ImmutableStore getStore() {
        if (storeInstance == null) {
            storeInstance = new S3ImmutableStore(s3, "idp-it");
        }
        return storeInstance;
    }

    @Override
    protected TenantId getTenantA() {
        return tenant;
    }

    @Override
    protected TenantId getTenantB() {
        return tenantB;
    }

    @Override
    protected void advancePastRetention() throws InterruptedException {
        Thread.sleep(1500); // retencion de 1s
    }

    @Override
    protected void hardDelete(TenantId tenantId, String path) {
        String key = tenantId.value() + "/" + path;
        var version = oldestVersion(key);
        s3.deleteObject(b -> b.bucket("idp-it").key(key).versionId(version.versionId()));
    }

    private static software.amazon.awssdk.services.s3.model.ObjectVersion oldestVersion(String key) {
        return s3.listObjectVersions(b -> b.bucket("idp-it").prefix(key)).versions().stream()
            .filter(v -> v.key().equals(key))
            .reduce((masReciente, anterior) -> anterior) // la lista viene de la mas reciente a la mas antigua
            .orElseThrow();
    }

    @Override
    protected byte[] retainedContent(TenantId tenantId, String path) throws IOException {
        String key = tenantId.value() + "/" + path;
        var version = oldestVersion(key);
        try (var in = s3.getObject(b -> b.bucket("idp-it").key(key).versionId(version.versionId()))) {
            return in.readAllBytes();
        }
    }

    @BeforeAll
    static void bucket() {
        s3 = S3Clients.create(URI.create(LOCALSTACK.getEndpoint().toString()), LOCALSTACK.getRegion(),
            LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey(), true);
        s3.createBucket(b -> b.bucket("idp-it").objectLockEnabledForBucket(true));
    }


    @Test
    void retencionYLegalHoldQuedanAplicados() throws NoSuchAlgorithmException, IOException {
        byte[] data = "inmutable".getBytes(StandardCharsets.UTF_8);
        S3ImmutableStore store = new S3ImmutableStore(s3, "idp-it");
        store.putWithRetention(tenant, "lock/b.txt", new ByteArrayInputStream(data), meta(data), Duration.ofDays(1));
        store.applyLegalHold(tenant, "lock/b.txt");

        var head = s3.headObject(HeadObjectRequest.builder().bucket("idp-it").key("t1/lock/b.txt").build());
        assertEquals(ObjectLockMode.COMPLIANCE, head.objectLockMode());
        var hold = s3.getObjectLegalHold(GetObjectLegalHoldRequest.builder().bucket("idp-it")
            .key("t1/lock/b.txt").build());
        assertEquals(ObjectLockLegalHoldStatus.ON, hold.legalHold().status());
    }
}
