package com.idp.kms.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.FailedPreconditionException;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionState;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.DestroyCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceClient.ListCryptoKeyVersionsPagedResponse;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.PublicKey;
import com.google.cloud.kms.v1.UpdateCryptoKeyVersionRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import io.grpc.Status;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifica lo que {@link SdkGcpKmsApi} envia al SDK; el cliente real no tiene emulador (spike T-00). */
class SdkGcpKmsApiTest {

    private static final String KEY = "projects/p/locations/l/keyRings/r/cryptoKeys/idp-x";

    private final KeyManagementServiceClient client = mock(KeyManagementServiceClient.class);
    private final AtomicLong clock = new AtomicLong();
    private final SdkGcpKmsApi api = new SdkGcpKmsApi(client, clock::get);

    private static Int64Value crc(byte[] data) {
        CRC32C c = new CRC32C();
        c.update(data);
        return Int64Value.of(c.getValue());
    }

    private static CryptoKeyVersion version(int n, CryptoKeyVersionState state) {
        return CryptoKeyVersion.newBuilder().setName(KEY + "/cryptoKeyVersions/" + n).setState(state).build();
    }

    private void versions(CryptoKeyVersion... vs) {
        ListCryptoKeyVersionsPagedResponse paged = mock(ListCryptoKeyVersionsPagedResponse.class);
        when(paged.iterateAll()).thenReturn(List.of(vs));
        when(client.listCryptoKeyVersions(any(ListCryptoKeyVersionsRequest.class))).thenReturn(paged);
    }

    private static ApiException grpc(Status.Code code) {
        GrpcStatusCode status = GrpcStatusCode.of(code);
        RuntimeException cause = new RuntimeException("detalle sensible projects/p/secreto");
        return switch (code) {
            case NOT_FOUND -> new NotFoundException(cause, status, false);
            case INVALID_ARGUMENT -> new InvalidArgumentException(cause, status, false);
            case FAILED_PRECONDITION -> new FailedPreconditionException(cause, status, false);
            default -> new PermissionDeniedException(cause, GrpcStatusCode.of(code), false);
        };
    }

    private void signResponse(byte[] signature) {
        when(client.asymmetricSign(any(AsymmetricSignRequest.class))).thenReturn(AsymmetricSignResponse.newBuilder()
            .setSignature(ByteString.copyFrom(signature)).setSignatureCrc32C(crc(signature))
            .setVerifiedDataCrc32C(true).build());
    }

    @Test
    void encryptEnviaLlavePlaintextAadYCrc() {
        byte[] ct = "ct".getBytes();
        when(client.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.newBuilder()
            .setCiphertext(ByteString.copyFrom(ct)).setCiphertextCrc32C(crc(ct))
            .setVerifiedPlaintextCrc32C(true).setVerifiedAdditionalAuthenticatedDataCrc32C(true).build());

        byte[] out = api.encrypt(KEY, new byte[] {1, 2}, new byte[] {9});

        ArgumentCaptor<EncryptRequest> req = ArgumentCaptor.forClass(EncryptRequest.class);
        verify(client).encrypt(req.capture());
        assertThat(req.getValue().getName()).isEqualTo(KEY);
        assertThat(req.getValue().getPlaintext().toByteArray()).containsExactly(1, 2);
        assertThat(req.getValue().getAdditionalAuthenticatedData().toByteArray()).containsExactly(9);
        assertThat(req.getValue().getPlaintextCrc32C()).isEqualTo(crc(new byte[] {1, 2}));
        assertThat(req.getValue().getAdditionalAuthenticatedDataCrc32C()).isEqualTo(crc(new byte[] {9}));
        assertThat(out).isEqualTo(ct);
    }

    @Test
    void encryptSinVerificacionCrcDelServidorFalla() {
        byte[] ct = "ct".getBytes();
        when(client.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.newBuilder()
            .setCiphertext(ByteString.copyFrom(ct)).setCiphertextCrc32C(crc(ct)).build());
        assertThatThrownBy(() -> api.encrypt(KEY, new byte[1], new byte[0])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptEnviaLlaveCiphertextAadYCrc() {
        byte[] plain = {7};
        when(client.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.newBuilder()
            .setPlaintext(ByteString.copyFrom(plain)).setPlaintextCrc32C(crc(plain)).build());

        byte[] out = api.decrypt(KEY, new byte[] {3}, new byte[] {9});

        ArgumentCaptor<DecryptRequest> req = ArgumentCaptor.forClass(DecryptRequest.class);
        verify(client).decrypt(req.capture());
        assertThat(req.getValue().getName()).isEqualTo(KEY);
        assertThat(req.getValue().getCiphertext().toByteArray()).containsExactly(3);
        assertThat(req.getValue().getAdditionalAuthenticatedData().toByteArray()).containsExactly(9);
        assertThat(req.getValue().getCiphertextCrc32C()).isEqualTo(crc(new byte[] {3}));
        assertThat(out).containsExactly(7);
    }

    @Test
    void decryptConCrcDeRespuestaIncorrectoFalla() {
        when(client.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.newBuilder()
            .setPlaintext(ByteString.copyFrom(new byte[] {7})).setPlaintextCrc32C(Int64Value.of(1)).build());
        assertThatThrownBy(() -> api.decrypt(KEY, new byte[1], new byte[0])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signUsaLaVersionHabilitadaMasAltaConLosDatosCrudosYDevuelveSuNumero() {
        versions(version(2, CryptoKeyVersionState.ENABLED), version(10, CryptoKeyVersionState.ENABLED),
            version(9, CryptoKeyVersionState.ENABLED));
        signResponse(new byte[] {5});

        GcpKmsApi.Signed signed = api.sign(KEY, new byte[] {1, 2, 3});

        ArgumentCaptor<ListCryptoKeyVersionsRequest> list = ArgumentCaptor.forClass(ListCryptoKeyVersionsRequest.class);
        verify(client).listCryptoKeyVersions(list.capture());
        assertThat(list.getValue().getParent()).isEqualTo(KEY);
        assertThat(list.getValue().getFilter()).isEqualTo("state = ENABLED");
        ArgumentCaptor<AsymmetricSignRequest> req = ArgumentCaptor.forClass(AsymmetricSignRequest.class);
        verify(client).asymmetricSign(req.capture());
        assertThat(req.getValue().getName()).isEqualTo(KEY + "/cryptoKeyVersions/10");
        assertThat(req.getValue().getData().toByteArray()).containsExactly(1, 2, 3);
        assertThat(req.getValue().getDataCrc32C()).isEqualTo(crc(new byte[] {1, 2, 3}));
        assertThat(signed.version()).isEqualTo(10);
        assertThat(signed.signature()).containsExactly(5);
    }

    @Test
    void signCacheaLaVersionConTtlYLaInvalidaAnteUnError() {
        versions(version(1, CryptoKeyVersionState.ENABLED));
        signResponse(new byte[] {5});

        api.sign(KEY, new byte[1]);
        api.sign(KEY, new byte[1]);
        verify(client, times(1)).listCryptoKeyVersions(any(ListCryptoKeyVersionsRequest.class));
        verify(client, times(2)).asymmetricSign(any(AsymmetricSignRequest.class));

        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        api.sign(KEY, new byte[1]);
        verify(client, times(2)).listCryptoKeyVersions(any(ListCryptoKeyVersionsRequest.class));

        when(client.asymmetricSign(any(AsymmetricSignRequest.class))).thenThrow(grpc(Status.Code.NOT_FOUND));
        assertThatThrownBy(() -> api.sign(KEY, new byte[1])).isInstanceOf(KeyService.KeyNotFoundException.class);
        signResponse(new byte[] {5});
        api.sign(KEY, new byte[1]);
        verify(client, times(3)).listCryptoKeyVersions(any(ListCryptoKeyVersionsRequest.class));
    }

    @Test
    void signSinVersionHabilitadaFalla() {
        versions();
        assertThatThrownBy(() -> api.sign(KEY, new byte[1])).isInstanceOf(IllegalStateException.class);
        verify(client, never()).asymmetricSign(any(AsymmetricSignRequest.class));
    }

    @Test
    void publicKeysDevuelveTodasLasVersionesHabilitadasConSuDerDelPem() throws Exception {
        byte[] spki1 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        byte[] spki3 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        versions(version(1, CryptoKeyVersionState.ENABLED), version(3, CryptoKeyVersionState.ENABLED));
        when(client.getPublicKey(KEY + "/cryptoKeyVersions/1")).thenReturn(ed25519(spki1));
        when(client.getPublicKey(KEY + "/cryptoKeyVersions/3")).thenReturn(ed25519(spki3));

        var keys = api.publicKeys(KEY);

        assertThat(keys).containsOnlyKeys(1, 3);
        assertThat(keys.get(1)).isEqualTo(spki1);
        assertThat(keys.get(3)).isEqualTo(spki3);
    }

    private static PublicKey ed25519(byte[] spki) {
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(spki)
            + "\n-----END PUBLIC KEY-----\n";
        return PublicKey.newBuilder().setPem(pem).setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519).build();
    }

    @Test
    void publicKeysRechazaUnAlgoritmoQueNoEsEd25519() {
        versions(version(1, CryptoKeyVersionState.ENABLED));
        when(client.getPublicKey(anyString())).thenReturn(PublicKey.newBuilder().setPem("x")
            .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256).build());
        assertThatThrownBy(() -> api.publicKeys(KEY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disableDeshabilitaYProgramaLaDestruccionSoloDeLasVersionesVivas() {
        versions(version(1, CryptoKeyVersionState.ENABLED), version(2, CryptoKeyVersionState.DISABLED),
            version(3, CryptoKeyVersionState.DESTROY_SCHEDULED), version(4, CryptoKeyVersionState.DESTROYED));

        api.disable(KEY);

        ArgumentCaptor<ListCryptoKeyVersionsRequest> list = ArgumentCaptor.forClass(ListCryptoKeyVersionsRequest.class);
        verify(client).listCryptoKeyVersions(list.capture());
        assertThat(list.getValue().getFilter()).isEmpty();
        ArgumentCaptor<UpdateCryptoKeyVersionRequest> update =
            ArgumentCaptor.forClass(UpdateCryptoKeyVersionRequest.class);
        verify(client).updateCryptoKeyVersion(update.capture());
        assertThat(update.getValue().getCryptoKeyVersion().getName()).isEqualTo(KEY + "/cryptoKeyVersions/1");
        assertThat(update.getValue().getCryptoKeyVersion().getState()).isEqualTo(CryptoKeyVersionState.DISABLED);
        assertThat(update.getValue().getUpdateMask().getPathsList()).containsExactly("state");
        ArgumentCaptor<DestroyCryptoKeyVersionRequest> destroy =
            ArgumentCaptor.forClass(DestroyCryptoKeyVersionRequest.class);
        verify(client, times(2)).destroyCryptoKeyVersion(destroy.capture());
        assertThat(destroy.getAllValues()).extracting(DestroyCryptoKeyVersionRequest::getName)
            .containsExactly(KEY + "/cryptoKeyVersions/1", KEY + "/cryptoKeyVersions/2");
    }

    @Test
    void notFoundSeTraduceAKeyNotFound() {
        when(client.encrypt(any(EncryptRequest.class))).thenThrow(grpc(Status.Code.NOT_FOUND));
        assertThatThrownBy(() -> api.encrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(KeyService.KeyNotFoundException.class);
    }

    @Test
    void invalidArgumentEnDecryptEsArgumentoInvalido() {
        when(client.decrypt(any(DecryptRequest.class))).thenThrow(grpc(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> api.decrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secreto");
    }

    @Test
    void invalidArgumentFueraDeDecryptEsUnavailableConElCodigo() {
        when(client.encrypt(any(EncryptRequest.class))).thenThrow(grpc(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> api.encrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(KeyServiceUnavailableException.class).hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    void failedPreconditionEsKekDeshabilitada() {
        when(client.decrypt(any(DecryptRequest.class))).thenThrow(grpc(Status.Code.FAILED_PRECONDITION));
        assertThatThrownBy(() -> api.decrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(KeyService.KeyDisabledException.class);
        when(client.encrypt(any(EncryptRequest.class))).thenThrow(grpc(Status.Code.FAILED_PRECONDITION));
        assertThatThrownBy(() -> api.encrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(KeyService.KeyDisabledException.class);
    }

    @Test
    void permissionDeniedYElRestoSonUnavailableSoloConElNombreDelCodigo() {
        when(client.decrypt(any(DecryptRequest.class))).thenThrow(grpc(Status.Code.PERMISSION_DENIED));
        assertThatThrownBy(() -> api.decrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(KeyServiceUnavailableException.class)
            .hasMessageContaining("PERMISSION_DENIED").hasMessageNotContaining("secreto").hasNoCause();
    }
}
