package com.idp.kms.gcp;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionState;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DestroyCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.PublicKey;
import com.google.cloud.kms.v1.UpdateCryptoKeyVersionRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.idp.kms.KeyService;
import java.util.Base64;
import java.util.function.Supplier;

/** {@link GcpKmsApi} sobre {@link KeyManagementServiceClient}. */
final class SdkGcpKmsApi implements GcpKmsApi {

    private final KeyManagementServiceClient client;

    SdkGcpKmsApi(KeyManagementServiceClient client) {
        this.client = client;
    }

    @Override
    public byte[] encrypt(String cryptoKey, byte[] plaintext, byte[] aad) {
        return mapNotFound(() -> client.encrypt(EncryptRequest.newBuilder().setName(cryptoKey)
            .setPlaintext(ByteString.copyFrom(plaintext))
            .setAdditionalAuthenticatedData(ByteString.copyFrom(aad)).build()).getCiphertext().toByteArray());
    }

    @Override
    public byte[] decrypt(String cryptoKey, byte[] ciphertext, byte[] aad) {
        return mapNotFound(() -> client.decrypt(DecryptRequest.newBuilder().setName(cryptoKey)
            .setCiphertext(ByteString.copyFrom(ciphertext))
            .setAdditionalAuthenticatedData(ByteString.copyFrom(aad)).build()).getPlaintext().toByteArray());
    }

    @Override
    public byte[] sign(String cryptoKey, byte[] data) {
        return mapNotFound(() -> client.asymmetricSign(AsymmetricSignRequest.newBuilder()
            .setName(signingVersion(cryptoKey).getName())
            .setData(ByteString.copyFrom(data)).build()).getSignature().toByteArray());
    }

    @Override
    public SigningKey signingKey(String cryptoKey) {
        return mapNotFound(() -> {
            String version = signingVersion(cryptoKey).getName();
            PublicKey key = client.getPublicKey(version);
            if (key.getAlgorithm() != CryptoKeyVersionAlgorithm.EC_SIGN_ED25519) {
                throw new IllegalStateException("Algoritmo de firma inesperado: " + key.getAlgorithm());
            }
            return new SigningKey(versionNumber(version), pemToDer(key.getPem()));
        });
    }

    @Override
    public void disable(String cryptoKey) {
        mapNotFound(() -> {
            for (CryptoKeyVersion v : client.listCryptoKeyVersions(listRequest(cryptoKey, null)).iterateAll()) {
                if (v.getState() == CryptoKeyVersionState.ENABLED) {
                    client.updateCryptoKeyVersion(UpdateCryptoKeyVersionRequest.newBuilder()
                        .setCryptoKeyVersion(CryptoKeyVersion.newBuilder().setName(v.getName())
                            .setState(CryptoKeyVersionState.DISABLED))
                        .setUpdateMask(FieldMask.newBuilder().addPaths("state")).build());
                }
                // Las versiones ya programadas o destruidas no admiten otra destruccion
                if (v.getState() == CryptoKeyVersionState.ENABLED || v.getState() == CryptoKeyVersionState.DISABLED) {
                    client.destroyCryptoKeyVersion(DestroyCryptoKeyVersionRequest.newBuilder()
                        .setName(v.getName()).build());
                }
            }
            return null;
        });
    }

    /** Las llaves asimetricas no tienen version primaria: se firma con la habilitada de numero mas alto. */
    private CryptoKeyVersion signingVersion(String cryptoKey) {
        Iterable<CryptoKeyVersion> versions =
            client.listCryptoKeyVersions(listRequest(cryptoKey, "state = ENABLED")).iterateAll();
        CryptoKeyVersion latest = null;
        for (CryptoKeyVersion v : versions) {
            if (latest == null || versionNumber(v.getName()) > versionNumber(latest.getName())) {
                latest = v;
            }
        }
        if (latest == null) {
            throw new IllegalStateException("Sin version habilitada para firmar");
        }
        return latest;
    }

    private static ListCryptoKeyVersionsRequest listRequest(String cryptoKey, String filter) {
        ListCryptoKeyVersionsRequest.Builder b = ListCryptoKeyVersionsRequest.newBuilder().setParent(cryptoKey);
        return (filter == null ? b : b.setFilter(filter)).build();
    }

    private static int versionNumber(String versionName) {
        return Integer.parseInt(versionName.substring(versionName.lastIndexOf('/') + 1));
    }

    private static byte[] pemToDer(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
    }

    private static <T> T mapNotFound(Supplier<T> call) {
        try {
            return call.get();
        } catch (NotFoundException e) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en Cloud KMS");
        }
    }
}
