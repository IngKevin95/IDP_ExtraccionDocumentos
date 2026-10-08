package com.idp.kms.aws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse;
import software.amazon.awssdk.services.kms.model.DisableKeyRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DisabledException;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KeyMetadata;
import software.amazon.awssdk.services.kms.model.KeyState;
import software.amazon.awssdk.services.kms.model.KmsInvalidSignatureException;
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException;
import software.amazon.awssdk.services.kms.model.NotFoundException;
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest;
import software.amazon.awssdk.services.kms.model.VerifyRequest;
import software.amazon.awssdk.services.kms.model.VerifyResponse;

/** Traduccion de errores y llamadas de {@link AwsKmsClientApi} con el KmsClient mockeado. */
class AwsKmsClientApiTest {

    private static final TenantId T1 = new TenantId("t1");

    // CALLS_REAL_METHODS: los metodos con lambda del cliente (default) delegan en los de peticion que se stubean.
    private final KmsClient client = mock(KmsClient.class, CALLS_REAL_METHODS);
    private final AwsKmsKeyService kms = new AwsKmsKeyService(client, 7);

    private void estado(KeyState state) {
        doReturn(DescribeKeyResponse.builder()
            .keyMetadata(KeyMetadata.builder().keyId("id-1").keyState(state).build()).build())
            .when(client).describeKey(any(DescribeKeyRequest.class));
    }

    @Test
    void verifyConFirmaInvalidaDevuelveFalse() {
        doThrow(KmsInvalidSignatureException.builder().build()).when(client).verify(any(VerifyRequest.class));
        assertFalse(kms.verify(T1, new byte[1], new byte[64], "firma"));
    }

    @Test
    void verifyValidoDevuelveTrue() {
        doReturn(VerifyResponse.builder().signatureValid(true).build()).when(client).verify(any(VerifyRequest.class));
        assertTrue(kms.verify(T1, new byte[1], new byte[64], "firma"));
    }

    @Test
    void publicKeysExtraeLos32BytesCrudosDelSpki() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        byte[] spki = new byte[44];
        byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        System.arraycopy(prefix, 0, spki, 0, prefix.length);
        System.arraycopy(raw, 0, spki, prefix.length, 32);
        doReturn(GetPublicKeyResponse.builder().publicKey(SdkBytes.fromByteArray(spki)).build())
            .when(client).getPublicKey(any(GetPublicKeyRequest.class));
        Map<Integer, byte[]> keys = kms.publicKeys(T1, "firma").orElseThrow();
        assertArrayEquals(raw, keys.get(1));
        assertEquals(1, keys.size());
    }

    @Test
    void erroresDelProveedorSeTraducen() {
        doThrow(NotFoundException.builder().build()).when(client).decrypt(any(DecryptRequest.class));
        assertThrows(KeyService.KeyNotFoundException.class, () -> kms.unwrapDek(T1, new byte[1], "datos", Map.of()));
        doThrow(DisabledException.builder().build()).when(client).decrypt(any(DecryptRequest.class));
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.unwrapDek(T1, new byte[1], "datos", Map.of()));
        doThrow(KmsInvalidStateException.builder().build()).when(client).decrypt(any(DecryptRequest.class));
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.unwrapDek(T1, new byte[1], "datos", Map.of()));
    }

    @Test
    void disableKekConLlaveYaPendienteDeBorradoEsExito() {
        estado(KeyState.PENDING_DELETION);
        doThrow(KmsInvalidStateException.builder().build()).when(client).disableKey(any(DisableKeyRequest.class));
        doThrow(KmsInvalidStateException.builder().build()).when(client)
            .scheduleKeyDeletion(any(ScheduleKeyDeletionRequest.class));
        kms.disableKek(T1, "datos");
    }

    @Test
    void disableKekConLlaveYaDeshabilitadaSoloProgramaElBorrado() {
        estado(KeyState.DISABLED);
        doThrow(KmsInvalidStateException.builder().build()).when(client).disableKey(any(DisableKeyRequest.class));
        doReturn(null).when(client).scheduleKeyDeletion(any(ScheduleKeyDeletionRequest.class));
        kms.disableKek(T1, "datos");
        verify(client).scheduleKeyDeletion(any(ScheduleKeyDeletionRequest.class));
    }

    @Test
    void estadoInvalidoQueNoEsElBuscadoSeReportaComoIndisponible() {
        estado(KeyState.ENABLED);
        doThrow(KmsInvalidStateException.builder().build()).when(client).disableKey(any(DisableKeyRequest.class));
        assertThrows(com.idp.kms.KeyServiceUnavailableException.class, () -> kms.disableKek(T1, "datos"));
        verify(client, never()).scheduleKeyDeletion(any(ScheduleKeyDeletionRequest.class));
    }

    @Test
    void disableKekDeLlaveInexistenteEsKeyNotFound() {
        doThrow(NotFoundException.builder().build()).when(client).describeKey(any(DescribeKeyRequest.class));
        assertThrows(KeyService.KeyNotFoundException.class, () -> kms.disableKek(T1, "datos"));
    }
}
