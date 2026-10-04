package com.idp.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectLegalHoldRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3StoreTest {

    private static final String SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String SHA_B64 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=";

    private final S3Client s3 = mock(S3Client.class);
    private final TenantId tenant = new TenantId("t1");
    private final ObjectMetadata meta = new ObjectMetadata(SHA, 3, "application/pdf");

    @Test
    void putGuardaBajoElPrefijoDelTenantConChecksumYContentType() {
        new S3ObjectStore(s3, "bkt").put(tenant, "docs/a.pdf", new ByteArrayInputStream(new byte[] {1, 2, 3}), meta);

        ArgumentCaptor<PutObjectRequest> cap = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(cap.capture(), any(RequestBody.class));
        PutObjectRequest r = cap.getValue();
        assertEquals("bkt", r.bucket());
        assertEquals("t1/docs/a.pdf", r.key());
        assertEquals(SHA_B64, r.checksumSHA256());
        assertEquals("application/pdf", r.contentType());
        assertEquals(3L, r.contentLength());
    }

    @Test
    void rutasQueEscapanDelPrefijoSeRechazan() {
        S3ObjectStore store = new S3ObjectStore(s3, "bkt");
        for (String bad : new String[] {"../t2/x", "a/../../t2/x", "/abs", "a//b", "a\\b", "", " ", "."}) {
            assertThrows(ObjectStore.StorageException.class,
                () -> store.get(tenant, bad), "ruta: " + bad);
        }
        assertThrows(ObjectStore.StorageException.class, () -> store.get(new TenantId("t1/../t2"), "x"));
        verify(s3, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void sha256InvalidoSeRechazaAntesDeSubir() {
        S3ObjectStore store = new S3ObjectStore(s3, "bkt");
        assertThrows(ObjectStore.StorageException.class, () -> store.put(tenant, "a", new ByteArrayInputStream(new byte[0]),
            new ObjectMetadata("zz", 0, null)));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void getDeObjetoInexistenteLanzaStorageException() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThrows(ObjectStore.StorageException.class, () -> new S3ObjectStore(s3, "bkt").get(tenant, "x"));
    }

    @Test
    void deleteUsaLaClaveDelTenantYTraduceErrores() {
        S3ObjectStore store = new S3ObjectStore(s3, "bkt");
        store.delete(tenant, "x");
        ArgumentCaptor<DeleteObjectRequest> cap = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(cap.capture());
        assertEquals("t1/x", cap.getValue().key());

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("denied").build());
        assertThrows(ObjectStore.StorageException.class, () -> store.delete(tenant, "x"));
    }

    @Test
    void putConRetencionFijaModoYFechaDeRetencion() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        S3ImmutableStore store = new S3ImmutableStore(s3, "bkt", ObjectLockMode.COMPLIANCE,
            Clock.fixed(now, ZoneOffset.UTC));

        store.putWithRetention(tenant, "a", new ByteArrayInputStream(new byte[] {1, 2, 3}), meta, Duration.ofDays(365));

        ArgumentCaptor<PutObjectRequest> cap = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(cap.capture(), any(RequestBody.class));
        assertEquals(ObjectLockMode.COMPLIANCE, cap.getValue().objectLockMode());
        assertEquals(now.plus(Duration.ofDays(365)), cap.getValue().objectLockRetainUntilDate());
    }

    @Test
    void retencionNoPositivaSeRechaza() {
        S3ImmutableStore store = new S3ImmutableStore(s3, "bkt");
        assertThrows(ObjectStore.StorageException.class, () -> store.putWithRetention(tenant, "a",
            new ByteArrayInputStream(new byte[0]), meta, Duration.ZERO));
    }

    @Test
    void legalHoldSeAplicaYSeLibera() {
        S3ImmutableStore store = new S3ImmutableStore(s3, "bkt");
        store.applyLegalHold(tenant, "a");
        store.removeLegalHold(tenant, "a");

        ArgumentCaptor<PutObjectLegalHoldRequest> cap = ArgumentCaptor.forClass(PutObjectLegalHoldRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).putObjectLegalHold(cap.capture());
        assertEquals("t1/a", cap.getAllValues().get(0).key());
        assertEquals(ObjectLockLegalHoldStatus.ON, cap.getAllValues().get(0).legalHold().status());
        assertEquals(ObjectLockLegalHoldStatus.OFF, cap.getAllValues().get(1).legalHold().status());
    }

    @Test
    void errorDelProveedorEnLegalHoldSeTraduce() {
        when(s3.putObjectLegalHold(any(PutObjectLegalHoldRequest.class)))
            .thenThrow(S3Exception.builder().message("x").build());
        assertThrows(ObjectStore.StorageException.class,
            () -> new S3ImmutableStore(s3, "bkt").applyLegalHold(tenant, "a"));
    }
}
