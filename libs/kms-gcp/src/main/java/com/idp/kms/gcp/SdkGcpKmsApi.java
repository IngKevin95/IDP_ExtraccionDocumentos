package com.idp.kms.gcp;

import com.google.api.gax.rpc.ApiException;
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
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.PublicKey;
import com.google.cloud.kms.v1.UpdateCryptoKeyVersionRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int64Value;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * {@link GcpKmsApi} sobre {@link KeyManagementServiceClient}. Verifica CRC32C en encrypt, decrypt y sign.
 * Limitacion de disable: solo trata versiones ENABLED y DISABLED; una version en PENDING_GENERATION o
 * PENDING_IMPORT no se destruye (el aprovisionamiento por OpenTofu no las deja en ese estado).
 */
final class SdkGcpKmsApi implements GcpKmsApi {

    /** Plazo de la version de firma cacheada: evita un list por firma; una rotacion se adopta en este plazo. */
    private static final long SIGN_VERSION_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private record SignVersion(String name, long loadedAt) {
    }

    private final KeyManagementServiceClient client;
    private final LongSupplier nanos;
    private final Map<String, SignVersion> signVersions = new ConcurrentHashMap<>();

    SdkGcpKmsApi(KeyManagementServiceClient client) {
        this(client, System::nanoTime);
    }

    SdkGcpKmsApi(KeyManagementServiceClient client, LongSupplier nanos) {
        this.client = client;
        this.nanos = nanos;
    }

    @Override
    public byte[] encrypt(String cryptoKey, byte[] plaintext, byte[] aad) {
        EncryptResponse r = translate(() -> client.encrypt(EncryptRequest.newBuilder().setName(cryptoKey)
            .setPlaintext(ByteString.copyFrom(plaintext)).setPlaintextCrc32C(crc(plaintext))
            .setAdditionalAuthenticatedData(ByteString.copyFrom(aad)).setAdditionalAuthenticatedDataCrc32C(crc(aad))
            .build()), false);
        byte[] ciphertext = r.getCiphertext().toByteArray();
        if (!r.getVerifiedPlaintextCrc32C() || !r.getVerifiedAdditionalAuthenticatedDataCrc32C()
                || !r.hasCiphertextCrc32C() || r.getCiphertextCrc32C().getValue() != crc(ciphertext).getValue()) {
            throw new IllegalStateException("Integridad CRC32C de encrypt no verificada");
        }
        return ciphertext;
    }

    @Override
    public byte[] decrypt(String cryptoKey, byte[] ciphertext, byte[] aad) {
        DecryptResponse r = translate(() -> client.decrypt(DecryptRequest.newBuilder().setName(cryptoKey)
            .setCiphertext(ByteString.copyFrom(ciphertext)).setCiphertextCrc32C(crc(ciphertext))
            .setAdditionalAuthenticatedData(ByteString.copyFrom(aad)).setAdditionalAuthenticatedDataCrc32C(crc(aad))
            .build()), true);
        byte[] plaintext = r.getPlaintext().toByteArray();
        if (!r.hasPlaintextCrc32C() || r.getPlaintextCrc32C().getValue() != crc(plaintext).getValue()) {
            Arrays.fill(plaintext, (byte) 0);
            throw new IllegalStateException("Integridad CRC32C de decrypt no verificada");
        }
        return plaintext;
    }

    @Override
    public Signed sign(String cryptoKey, byte[] data) {
        try {
            String version = signingVersion(cryptoKey);
            AsymmetricSignResponse r = translate(() -> client.asymmetricSign(AsymmetricSignRequest.newBuilder()
                .setName(version).setData(ByteString.copyFrom(data)).setDataCrc32C(crc(data)).build()), false);
            byte[] signature = r.getSignature().toByteArray();
            if (!r.getVerifiedDataCrc32C() || !r.hasSignatureCrc32C()
                    || r.getSignatureCrc32C().getValue() != crc(signature).getValue()) {
                throw new IllegalStateException("Integridad CRC32C de sign no verificada");
            }
            return new Signed(versionNumber(version), signature);
        } catch (RuntimeException e) {
            signVersions.remove(cryptoKey);
            throw e;
        }
    }

    @Override
    public Map<Integer, byte[]> publicKeys(String cryptoKey) {
        return translate(() -> {
            Map<Integer, byte[]> out = new HashMap<>();
            for (CryptoKeyVersion v : client.listCryptoKeyVersions(listRequest(cryptoKey, "state = ENABLED"))
                    .iterateAll()) {
                PublicKey key = client.getPublicKey(v.getName());
                if (key.getAlgorithm() != CryptoKeyVersionAlgorithm.EC_SIGN_ED25519) {
                    throw new IllegalStateException("Algoritmo de firma inesperado: " + key.getAlgorithm());
                }
                out.put(versionNumber(v.getName()), pemToDer(key.getPem()));
            }
            return out;
        }, false);
    }

    @Override
    public void disable(String cryptoKey) {
        signVersions.remove(cryptoKey);
        translate(() -> {
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
        }, false);
    }

    /** Las llaves asimetricas no tienen version primaria: se firma con la habilitada de numero mas alto. */
    private String signingVersion(String cryptoKey) {
        long now = nanos.getAsLong();
        SignVersion cached = signVersions.get(cryptoKey);
        if (cached != null && now - cached.loadedAt() < SIGN_VERSION_TTL_NANOS) {
            return cached.name();
        }
        String latest = translate(() -> {
            String best = null;
            for (CryptoKeyVersion v : client.listCryptoKeyVersions(listRequest(cryptoKey, "state = ENABLED"))
                    .iterateAll()) {
                if (best == null || versionNumber(v.getName()) > versionNumber(best)) {
                    best = v.getName();
                }
            }
            return best;
        }, false);
        if (latest == null) {
            throw new IllegalStateException("Sin version habilitada para firmar");
        }
        signVersions.put(cryptoKey, new SignVersion(latest, now));
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

    private static Int64Value crc(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return Int64Value.of(crc.getValue());
    }

    /**
     * Traduce el codigo gRPC: NOT_FOUND a llave inexistente, FAILED_PRECONDITION (version deshabilitada o
     * destruida) a KEK deshabilitada y, solo en decrypt, INVALID_ARGUMENT (AAD distinto o texto manipulado) a
     * argumento invalido. El resto es un fallo del proveedor y solo expone el nombre del codigo.
     */
    private static <T> T translate(Supplier<T> call, boolean invalidArgumentIsCallerError) {
        try {
            return call.get();
        } catch (ApiException e) {
            switch (e.getStatusCode().getCode()) {
                case NOT_FOUND -> throw new KeyService.KeyNotFoundException("Llave inexistente en Cloud KMS");
                case FAILED_PRECONDITION ->
                    throw new KeyService.KeyDisabledException("Version de la llave deshabilitada o destruida");
                case INVALID_ARGUMENT -> {
                    if (invalidArgumentIsCallerError) {
                        throw new IllegalArgumentException("Texto cifrado o AAD invalidos");
                    }
                }
                default -> { }
            }
            throw new KeyServiceUnavailableException("Cloud KMS respondio " + e.getStatusCode().getCode());
        }
    }
}
