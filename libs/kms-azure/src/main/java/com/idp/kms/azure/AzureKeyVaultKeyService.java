package com.idp.kms.azure;

import com.idp.kms.AadContext;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * KeyService sobre Azure Key Vault (ADR 0032).
 * <ul>
 *   <li>Aislamiento por tenant: llave {@link KeyNames#hashed} (Key Vault no admite guion bajo). Las llaves las
 *       aprovisiona OpenTofu/onboarding; el adaptador nunca las crea. Datos: RSA de 3072 bits; firma: EC P-256.</li>
 *   <li>wrap/unwrap con RSA-OAEP-256 (AES-KW solo existe en Managed HSM) sobre {@code DEK || SHA-256(AAD)}: Key Vault
 *       no tiene AAD, asi que el AAD se enlaza al DEK con ese sufijo, que se compara en tiempo constante (SEC-056).</li>
 *   <li>Firma ES256 (r||s de 64 bytes). No hay llave publica local: {@link #publicKeys} es vacio y se verifica con
 *       el proveedor (SEC-055).</li>
 *   <li>disableKek: deshabilita de inmediato en este proceso y luego en Key Vault (enabled=false y borrado; el
 *       purgado definitivo lo gobierna la purge protection). El llamador respeta el legal hold (SEC-016).</li>
 * </ul>
 * Cualquier fallo del proveedor es {@link KeyServiceUnavailableException}, sin datos del mensaje del SDK.
 */
public final class AzureKeyVaultKeyService implements KeyService {

    private static final int HASH_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;

    private final KeyVaultApi api;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    AzureKeyVaultKeyService(KeyVaultApi api) {
        this.api = api;
    }

    private String checked(TenantId tenantId, String keyId) {
        String name = KeyNames.hashed(tenantId, keyId);
        if (disabled.contains(name)) {
            throw new KeyDisabledException("KEK deshabilitada");
        }
        return name;
    }

    @Override
    public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        byte[] material = concat(dek, sha256(AadContext.canonical(aadContext)));
        try {
            return new CryptoResult(call(() -> api.wrap(name, material)));
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    @Override
    public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId,
                                  Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        byte[] plain = call(() -> api.unwrap(name, wrappedDek));
        byte[] dek = null;
        try {
            if (plain == null || plain.length < HASH_LENGTH) {
                throw new IllegalArgumentException("Texto cifrado invalido");
            }
            int dekLength = plain.length - HASH_LENGTH;
            byte[] storedHash = Arrays.copyOfRange(plain, dekLength, plain.length);
            if (!MessageDigest.isEqual(storedHash, sha256(AadContext.canonical(aadContext)))) {
                throw new IllegalArgumentException("No se pudo descifrar: AAD no coincide");
            }
            dek = Arrays.copyOf(plain, dekLength);
            return new CryptoResult(dek);
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    @Override
    public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
        String name = checked(tenantId, keyId);
        byte[] signature = call(() -> api.sign(name, data));
        if (signature == null || signature.length != SIGNATURE_LENGTH) {
            throw new KeyServiceUnavailableException("Respuesta inesperada del KMS");
        }
        return new CryptoResult(signature);
    }

    @Override
    public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
        String name = checked(tenantId, keyId);
        return call(() -> api.verify(name, data, signature));
    }

    /** Key Vault no expone una llave publica en el formato local (solo Ed25519): se verifica con {@link #verify}. */
    @Override
    public Optional<Map<Integer, byte[]>> publicKeys(TenantId tenantId, String keyId) {
        KeyNames.check(tenantId, keyId);
        return Optional.empty();
    }

    @Override
    public SignatureAlgorithm signatureAlgorithm(TenantId tenantId, String keyId) {
        KeyNames.check(tenantId, keyId);
        return SignatureAlgorithm.ES256;
    }

    @Override
    public void disableKek(TenantId tenantId, String kekId) {
        String name = KeyNames.hashed(tenantId, kekId);
        disabled.add(name);
        call(() -> {
            api.disable(name);
            return null;
        });
    }

    /** Traduce los fallos del proveedor; solo el nombre de la clase del error, sin mensaje (podria traer PII). */
    private static <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (KeyNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KeyServiceUnavailableException("KMS no disponible: " + e.getClass().getSimpleName(), e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
