package com.idp.kms.aws;

import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * KeyService sobre AWS KMS.
 * <ul>
 *   <li>Aislamiento por tenant: cada (tenant, llave) es el alias {@code alias/idp/t-<tenant>-<keyId>}.</li>
 *   <li>wrap/unwrap con Encrypt/Decrypt; el AAD viaja como {@code EncryptionContext} nativo.</li>
 *   <li>sign/verify con llaves ECC_NIST_EDWARDS25519 (ED25519_SHA_512, mensaje RAW).</li>
 *   <li>disableKek: marca la KEK como deshabilitada de inmediato en este proceso, y luego DisableKey y
 *       ScheduleKeyDeletion (irreversible al cierre de la ventana). El llamador respeta el legal hold
 *       (SEC-016/017).</li>
 * </ul>
 * Cualquier fallo del proveedor se reporta como {@link KeyServiceUnavailableException} sin datos del error
 * salvo el nombre de su clase (AC-13).
 */
public final class AwsKmsKeyService implements KeyService {

    /** Ventana minima de borrado programado de AWS KMS. */
    static final int MIN_DELETION_WINDOW_DAYS = 7;

    private static final byte[] SPKI_ED25519_PREFIX =
        {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final int ED25519_KEY_LENGTH = 32;

    private final AwsKmsApi api;
    private final int deletionWindowDays;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    public AwsKmsKeyService(KmsClient client, int deletionWindowDays) {
        this(new AwsKmsClientApi(client), deletionWindowDays);
    }

    AwsKmsKeyService(AwsKmsApi api, int deletionWindowDays) {
        this.api = api;
        this.deletionWindowDays = Math.max(MIN_DELETION_WINDOW_DAYS, deletionWindowDays);
    }

    static String alias(TenantId tenantId, String keyId) {
        KeyNames.check(tenantId, keyId);
        return "alias/idp/t-" + tenantId.value() + "-" + keyId;
    }

    private String checked(TenantId tenantId, String keyId) {
        String alias = alias(tenantId, keyId);
        if (disabled.contains(alias)) {
            throw new KeyDisabledException("KEK deshabilitada");
        }
        return alias;
    }

    @Override
    public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) {
        String alias = checked(tenantId, kekId);
        return new CryptoResult(call(() -> api.encrypt(alias, dek, encryptionContext(aadContext))));
    }

    @Override
    public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId,
                                  Map<String, String> aadContext) {
        String alias = checked(tenantId, kekId);
        byte[] dek = call(() -> api.decrypt(alias, wrappedDek, encryptionContext(aadContext)));
        try {
            return new CryptoResult(dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
        String alias = checked(tenantId, keyId);
        return new CryptoResult(call(() -> api.sign(alias, data)));
    }

    @Override
    public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
        String alias = checked(tenantId, keyId);
        return call(() -> api.verify(alias, data, signature));
    }

    @Override
    public Optional<Map<Integer, byte[]>> publicKeys(TenantId tenantId, String keyId) {
        String alias = checked(tenantId, keyId);
        byte[] spki = call(() -> api.publicKey(alias));
        int prefix = SPKI_ED25519_PREFIX.length;
        if (spki.length != prefix + ED25519_KEY_LENGTH
                || !Arrays.equals(spki, 0, prefix, SPKI_ED25519_PREFIX, 0, prefix)) {
            throw new KeyServiceUnavailableException("Respuesta inesperada del KMS");
        }
        // Las llaves asimetricas de KMS no rotan: siempre hay una unica version.
        return Optional.of(Map.of(1, Arrays.copyOfRange(spki, prefix, spki.length)));
    }

    @Override
    public SignatureAlgorithm signatureAlgorithm(TenantId tenantId, String keyId) {
        KeyNames.check(tenantId, keyId);
        return SignatureAlgorithm.ED25519;
    }

    @Override
    public void disableKek(TenantId tenantId, String kekId) {
        String alias = alias(tenantId, kekId);
        disabled.add(alias);
        call(() -> {
            api.disableAndScheduleDeletion(alias, deletionWindowDays);
            return null;
        });
    }

    private static Map<String, String> encryptionContext(Map<String, String> aad) {
        Map<String, String> context = new HashMap<>();
        if (aad != null) {
            // AadContext.canonical trata el valor nulo como vacio; KMS no admite nulos.
            aad.forEach((k, v) -> context.put(k, v == null ? "" : v));
        }
        return context;
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (KeyNotFoundException | KeyDisabledException | KeyServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Sin causa adjunta: el mensaje del SDK podria arrastrar datos del contexto o de la cuenta.
            throw new KeyServiceUnavailableException("KMS no disponible: " + e.getClass().getSimpleName());
        }
    }
}
