package com.idp.kms.azure;

import com.idp.kms.AadContext;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
 * Los fallos de disponibilidad (5xx, 429, red, 401/403 de configuracion) son {@link KeyServiceUnavailableException},
 * sin mensaje ni causa del SDK. Un 403/409 por llave deshabilitada o eliminada es {@link KeyDisabledException}: entre
 * replicas la garantia la da el proveedor, la marca en proceso solo acelera la respuesta local.
 */
public final class AzureKeyVaultKeyService implements KeyService {

    private static final int HASH_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;
    private static final byte FORMAT = 1;
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9]{1,64}");

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
            KeyVaultApi.Wrapped w = call(() -> api.wrap(name, material));
            return new CryptoResult(encode(w.version(), w.ciphertext()));
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    /**
     * Un unico tipo y mensaje de fallo para AAD distinto, blob manipulado/truncado/de otra llave o version
     * inexistente: no se filtra la causa.
     */
    @Override
    public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId,
                                  Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        String[] version = new String[1];
        byte[] ciphertext = decode(wrappedDek, version);
        byte[] plain;
        try {
            plain = call(() -> api.unwrap(name, version[0], ciphertext));
        } catch (CiphertextRejectedException e) {
            throw decryptFailure();
        }
        byte[] dek = null;
        try {
            if (plain == null || plain.length <= HASH_LENGTH) {
                throw decryptFailure();
            }
            int dekLength = plain.length - HASH_LENGTH;
            byte[] storedHash = Arrays.copyOfRange(plain, dekLength, plain.length);
            if (!MessageDigest.isEqual(storedHash, sha256(AadContext.canonical(aadContext)))) {
                throw decryptFailure();
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

    private static IllegalArgumentException decryptFailure() {
        return new IllegalArgumentException("No se pudo descifrar");
    }

    /** Formato del blob: {@code 0x01 | longitud de version (1 byte) | version ASCII | texto cifrado} (plan 8). */
    private static byte[] encode(String version, byte[] ciphertext) {
        byte[] v = version.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[2 + v.length + ciphertext.length];
        out[0] = FORMAT;
        out[1] = (byte) v.length;
        System.arraycopy(v, 0, out, 2, v.length);
        System.arraycopy(ciphertext, 0, out, 2 + v.length, ciphertext.length);
        return out;
    }

    private static byte[] decode(byte[] blob, String[] versionOut) {
        if (blob == null || blob.length < 3 || blob[0] != FORMAT) {
            throw decryptFailure();
        }
        int len = blob[1] & 0xff;
        if (len == 0 || 2 + len >= blob.length) {
            throw decryptFailure();
        }
        String version = new String(blob, 2, len, StandardCharsets.US_ASCII);
        if (!VERSION.matcher(version).matches()) {
            throw decryptFailure();
        }
        versionOut[0] = version;
        return Arrays.copyOfRange(blob, 2 + len, blob.length);
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
        // La firma la controla el atacante: una de largo invalido no llega al proveedor.
        if (signature == null || signature.length != SIGNATURE_LENGTH) {
            return false;
        }
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
        } catch (KeyNotFoundException | KeyDisabledException | CiphertextRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Sin encadenar la causa: los mensajes del SDK pueden traer ids de tenant/cliente al log.
            throw new KeyServiceUnavailableException("KMS no disponible: " + e.getClass().getSimpleName());
        }
    }

    /** Tope del material a envolver: RSA-OAEP-256 con llave de 3072 bits admite 318 bytes, hash incluido. */
    private static final int MAX_PLAIN_BYTES = 256;

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length > MAX_PLAIN_BYTES || b.length > MAX_PLAIN_BYTES) {
            throw new IllegalArgumentException("Material a envolver demasiado grande");
        }
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
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
