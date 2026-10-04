package com.idp.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
class S3StoreIntegrationTest {

    @Container
    static final LocalStackContainer LOCALSTACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices("s3");

    private static S3Client s3;
    private final TenantId tenant = new TenantId("t1");

    @BeforeAll
    static void bucket() {
        s3 = S3Clients.create(URI.create(LOCALSTACK.getEndpoint().toString()), LOCALSTACK.getRegion(),
            LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey(), true);
        s3.createBucket(b -> b.bucket("idp-it").objectLockEnabledForBucket(true));
    }

    private static ObjectMetadata meta(byte[] data) throws NoSuchAlgorithmException {
        return new ObjectMetadata(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
            data.length, "text/plain");
    }

    @Test
    void putYGetRoundTripBajoPrefijoDelTenant() throws Exception {
        byte[] data = "contenido".getBytes(StandardCharsets.UTF_8);
        S3ObjectStore store = new S3ObjectStore(s3, "idp-it");
        store.put(tenant, "docs/a.txt", new ByteArrayInputStream(data), meta(data));
        try (var in = store.get(tenant, "docs/a.txt")) {
            assertArrayEquals(data, in.readAllBytes());
        }
        s3.headObject(HeadObjectRequest.builder().bucket("idp-it").key("t1/docs/a.txt").build());
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
