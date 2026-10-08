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
import com.google.api.gax.rpc.NotFoundException;
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
import com.idp.kms.KeyService;
import io.grpc.Status;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifica lo que {@link SdkGcpKmsApi} envia al SDK; el cliente real no tiene emulador (spike T-00). */
class SdkGcpKmsApiTest {

    private static final String KEY = "projects/p/locations/l/keyRings/r/cryptoKeys/idp-x";

    private final KeyManagementServiceClient client = mock(KeyManagementServiceClient.class);
    private final SdkGcpKmsApi api = new SdkGcpKmsApi(client);

    private static CryptoKeyVersion version(int n, CryptoKeyVersionState state) {
        return CryptoKeyVersion.newBuilder().setName(KEY + "/cryptoKeyVersions/" + n).setState(state).build();
    }

    private void versions(CryptoKeyVersion... vs) {
        ListCryptoKeyVersionsPagedResponse paged = mock(ListCryptoKeyVersionsPagedResponse.class);
        when(paged.iterateAll()).thenReturn(List.of(vs));
        when(client.listCryptoKeyVersions(any(ListCryptoKeyVersionsRequest.class))).thenReturn(paged);
    }

    @Test
    void encryptEnviaLlavePlaintextYAad() {
        when(client.encrypt(any(EncryptRequest.class)))
            .thenReturn(EncryptResponse.newBuilder().setCiphertext(ByteString.copyFromUtf8("ct")).build());

        byte[] out = api.encrypt(KEY, new byte[] {1, 2}, new byte[] {9});

        ArgumentCaptor<EncryptRequest> req = ArgumentCaptor.forClass(EncryptRequest.class);
        verify(client).encrypt(req.capture());
        assertThat(req.getValue().getName()).isEqualTo(KEY);
        assertThat(req.getValue().getPlaintext().toByteArray()).containsExactly(1, 2);
        assertThat(req.getValue().getAdditionalAuthenticatedData().toByteArray()).containsExactly(9);
        assertThat(out).isEqualTo("ct".getBytes());
    }

    @Test
    void decryptEnviaLlaveCiphertextYAad() {
        when(client.decrypt(any(DecryptRequest.class)))
            .thenReturn(DecryptResponse.newBuilder().setPlaintext(ByteString.copyFrom(new byte[] {7})).build());

        byte[] out = api.decrypt(KEY, new byte[] {3}, new byte[] {9});

        ArgumentCaptor<DecryptRequest> req = ArgumentCaptor.forClass(DecryptRequest.class);
        verify(client).decrypt(req.capture());
        assertThat(req.getValue().getName()).isEqualTo(KEY);
        assertThat(req.getValue().getCiphertext().toByteArray()).containsExactly(3);
        assertThat(req.getValue().getAdditionalAuthenticatedData().toByteArray()).containsExactly(9);
        assertThat(out).containsExactly(7);
    }

    @Test
    void signUsaLaVersionHabilitadaMasAltaConLosDatosCrudos() {
        versions(version(2, CryptoKeyVersionState.ENABLED), version(10, CryptoKeyVersionState.ENABLED),
            version(9, CryptoKeyVersionState.ENABLED));
        when(client.asymmetricSign(any(AsymmetricSignRequest.class)))
            .thenReturn(AsymmetricSignResponse.newBuilder().setSignature(ByteString.copyFrom(new byte[] {5})).build());

        byte[] sig = api.sign(KEY, new byte[] {1, 2, 3});

        ArgumentCaptor<ListCryptoKeyVersionsRequest> list = ArgumentCaptor.forClass(ListCryptoKeyVersionsRequest.class);
        verify(client).listCryptoKeyVersions(list.capture());
        assertThat(list.getValue().getParent()).isEqualTo(KEY);
        assertThat(list.getValue().getFilter()).isEqualTo("state = ENABLED");
        ArgumentCaptor<AsymmetricSignRequest> req = ArgumentCaptor.forClass(AsymmetricSignRequest.class);
        verify(client).asymmetricSign(req.capture());
        assertThat(req.getValue().getName()).isEqualTo(KEY + "/cryptoKeyVersions/10");
        assertThat(req.getValue().getData().toByteArray()).containsExactly(1, 2, 3);
        assertThat(sig).containsExactly(5);
    }

    @Test
    void signSinVersionHabilitadaFalla() {
        versions();
        assertThatThrownBy(() -> api.sign(KEY, new byte[1])).isInstanceOf(IllegalStateException.class);
        verify(client, never()).asymmetricSign(any(AsymmetricSignRequest.class));
    }

    @Test
    void signingKeyDevuelveVersionYDerDelPem() throws Exception {
        byte[] spki = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(spki)
            + "\n-----END PUBLIC KEY-----\n";
        versions(version(1, CryptoKeyVersionState.ENABLED), version(3, CryptoKeyVersionState.ENABLED));
        when(client.getPublicKey(anyString())).thenReturn(PublicKey.newBuilder().setPem(pem)
            .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519).build());

        GcpKmsApi.SigningKey key = api.signingKey(KEY);

        verify(client).getPublicKey(KEY + "/cryptoKeyVersions/3");
        assertThat(key.version()).isEqualTo(3);
        assertThat(key.spki()).isEqualTo(spki);
    }

    @Test
    void signingKeyRechazaUnAlgoritmoQueNoEsEd25519() {
        versions(version(1, CryptoKeyVersionState.ENABLED));
        when(client.getPublicKey(anyString())).thenReturn(PublicKey.newBuilder().setPem("x")
            .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256).build());
        assertThatThrownBy(() -> api.signingKey(KEY)).isInstanceOf(IllegalStateException.class);
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
    void notFoundDelSdkSeTraduceAKeyNotFound() {
        when(client.encrypt(any(EncryptRequest.class))).thenThrow(new NotFoundException(
            new RuntimeException("x"), GrpcStatusCode.of(Status.Code.NOT_FOUND), false));
        assertThatThrownBy(() -> api.encrypt(KEY, new byte[1], new byte[0]))
            .isInstanceOf(KeyService.KeyNotFoundException.class);
    }
}
