package com.idp.kms.gcp;

import com.idp.kms.AadContext;
import com.idp.kms.Ed25519Verifier;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * KeyService sobre Cloud KMS. Una CryptoKey por (tenant, id) con nombre {@link KeyNames#hashed} (el id de
 * CryptoKey admite 63 caracteres). Las llaves las aprovisiona OpenTofu/onboarding: el adaptador no las crea.
 * <ul>
 *   <li>wrap/unwrap: encrypt/decrypt simetrico con el AAD canonico como {@code additionalAuthenticatedData}.</li>
 *   <li>sign: Ed25519 en el KMS; Cloud KMS no verifica en servidor, asi que verify usa la llave publica
 *       (cacheada con TTL) y el verificador local de kms-port.</li>
 *   <li>disableKek: marca la KEK deshabilitada de inmediato en este proceso aunque el proveedor falle, deshabilita
 *       las versiones y programa su destruccion en la ventana de la llave (SEC-016).</li>
 * </ul>
 * Cualquier fallo del proveedor se reporta como {@link KeyServiceUnavailableException} con solo el nombre de la
 * clase del error: los mensajes del SDK pueden incluir nombres de recurso o detalles de credenciales.
 */
public final class GcpKmsKeyService implements KeyService {

    /** Una rotacion de la llave de firma se refleja en verify tras este plazo. */
    private static final long KEY_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final byte[] ED25519_SPKI_PREFIX =
        {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private record CachedKey(int version, byte[] raw, long loadedAt) {
    }

    private final GcpKmsApi api;
    private final String keyRingPath;
    private final LongSupplier nanos;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedKey> publicKeys = new ConcurrentHashMap<>();

    GcpKmsKeyService(GcpKmsApi api, String projectId, String location, String keyRing) {
        this(api, projectId, location, keyRing, System::nanoTime);
    }

    GcpKmsKeyService(GcpKmsApi api, String projectId, String location, String keyRing, LongSupplier nanos) {
        this.api = Objects.requireNonNull(api);
        this.keyRingPath = "projects/" + projectId + "/locations/" + location + "/keyRings/" + keyRing;
        this.nanos = Objects.requireNonNull(nanos);
    }

    private String cryptoKey(TenantId tenantId, String keyId) {
        // KeyNames.hashed da 68 caracteres y el id de CryptoKey admite 63: se conserva "idp-" + 59 hex (236 bits)
        return keyRingPath + "/cryptoKeys/" + KeyNames.hashed(tenantId, keyId);
    }

    private String checked(TenantId tenantId, String keyId) {
        String name = cryptoKey(tenantId, keyId);
        if (disabled.contains(name)) {
            throw new KeyDisabledException("KEK deshabilitada");
        }
        return name;
    }

    @Override
    public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        byte[] aad = AadContext.canonical(aadContext);
        return new CryptoResult(call(() -> api.encrypt(name, dek, aad)));
    }

    @Override
    public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId,
                                  Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        byte[] aad = AadContext.canonical(aadContext);
        byte[] dek = call(() -> api.decrypt(name, wrappedDek, aad));
        try {
            return new CryptoResult(dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
        String name = checked(tenantId, keyId);
        return new CryptoResult(call(() -> api.sign(name, data)));
    }

    @Override
    public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
        CachedKey key = publicKey(checked(tenantId, keyId));
        return Ed25519Verifier.verify(Map.of(key.version(), key.raw()), data, signature);
    }

    @Override
    public Optional<Map<Integer, byte[]>> publicKeys(TenantId tenantId, String keyId) {
        CachedKey key = publicKey(checked(tenantId, keyId));
        return Optional.of(Map.of(key.version(), key.raw().clone()));
    }

    @Override
    public SignatureAlgorithm signatureAlgorithm(TenantId tenantId, String keyId) {
        KeyNames.check(tenantId, keyId);
        return SignatureAlgorithm.ED25519;
    }

    @Override
    public void disableKek(TenantId tenantId, String kekId) {
        String name = cryptoKey(tenantId, kekId);
        disabled.add(name);
        publicKeys.remove(name);
        call(() -> {
            api.disable(name);
            return null;
        });
    }

    private CachedKey publicKey(String name) {
        long now = nanos.getAsLong();
        CachedKey cached = publicKeys.get(name);
        if (cached != null && now - cached.loadedAt() < KEY_TTL_NANOS) {
            return cached;
        }
        CachedKey fresh = call(() -> {
            GcpKmsApi.SigningKey key = api.signingKey(name);
            return new CachedKey(key.version(), rawEd25519(key.spki()), now);
        });
        publicKeys.put(name, fresh);
        return fresh;
    }

    private static byte[] rawEd25519(byte[] spki) {
        int prefix = ED25519_SPKI_PREFIX.length;
        if (spki.length != prefix + 32 || !Arrays.equals(spki, 0, prefix, ED25519_SPKI_PREFIX, 0, prefix)) {
            throw new IllegalStateException("Llave publica Ed25519 inesperada");
        }
        return Arrays.copyOfRange(spki, prefix, spki.length);
    }

    private static <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (KeyNotFoundException | KeyDisabledException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KeyServiceUnavailableException("Cloud KMS no disponible: " + e.getClass().getSimpleName());
        }
    }
}
