package com.idp.kms.gcp;

import com.idp.kms.AadContext;
import com.idp.kms.Ed25519Verifier;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
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
 *   <li>sign: Ed25519 en el KMS (datos de hasta 64 KiB). La salida es {@code vault:v<N>:<base64>}, el formato que
 *       ya entiende {@code Ed25519Verifier}, con N la version de la CryptoKey que firmo. Cloud KMS no verifica en
 *       servidor: verify usa las llaves publicas de todas las versiones habilitadas (cacheadas con TTL), asi que las
 *       firmas anteriores a una rotacion siguen verificando. Si una firma no verifica se refresca el cache una vez
 *       por ventana antes de dar false. Politica operativa: no deshabilitar ni destruir versiones de la llave de
 *       auditoria salvo shredding total (plan multicloud, seccion 8).</li>
 *   <li>disableKek: marca la KEK deshabilitada de inmediato en este proceso aunque el proveedor falle, deshabilita
 *       las versiones y programa su destruccion en la ventana de la llave (SEC-016).</li>
 * </ul>
 * Cualquier fallo del proveedor se reporta como {@link KeyServiceUnavailableException} con solo el nombre de la
 * clase del error: los mensajes del SDK pueden incluir nombres de recurso o detalles de credenciales.
 */
public final class GcpKmsKeyService implements KeyService {

    /** Una rotacion hecha por otro proceso se refleja en verify tras este plazo, o antes si una firma no verifica. */
    private static final long KEY_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    /** Un verify fallido refresca las llaves publicas como maximo una vez por este plazo (anti-amplificacion). */
    private static final long REFRESH_MIN_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** Limite de AsymmetricSign de Cloud KMS para datos crudos. */
    static final int MAX_SIGN_BYTES = 64 * 1024;
    private static final String LABEL = "vault:v";
    private static final byte[] ED25519_SPKI_PREFIX =
        {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private record CachedKeys(Map<Integer, byte[]> raw, long loadedAt) {
    }

    private final GcpKmsApi api;
    private final String keyRingPath;
    private final LongSupplier nanos;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedKeys> publicKeys = new ConcurrentHashMap<>();

    GcpKmsKeyService(GcpKmsApi api, String projectId, String location, String keyRing) {
        this(api, projectId, location, keyRing, System::nanoTime);
    }

    GcpKmsKeyService(GcpKmsApi api, String projectId, String location, String keyRing, LongSupplier nanos) {
        this.api = Objects.requireNonNull(api);
        this.keyRingPath = "projects/" + projectId + "/locations/" + location + "/keyRings/" + keyRing;
        this.nanos = Objects.requireNonNull(nanos);
    }

    private String cryptoKey(TenantId tenantId, String keyId) {
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
        if (data == null || data.length > MAX_SIGN_BYTES) {
            throw new IllegalArgumentException("Los datos a firmar no pueden superar " + MAX_SIGN_BYTES + " bytes");
        }
        GcpKmsApi.Signed signed = call(() -> api.sign(name, data));
        CachedKeys cached = publicKeys.get(name);
        if (cached != null && !cached.raw().containsKey(signed.version())) {
            publicKeys.remove(name); // rotacion: la proxima verificacion recarga las versiones
        }
        String label = LABEL + signed.version() + ":" + Base64.getEncoder().encodeToString(signed.signature());
        return new CryptoResult(label.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
        String name = checked(tenantId, keyId);
        CachedKeys keys = keys(name);
        if (matches(keys.raw(), data, signature)) {
            return true;
        }
        if (nanos.getAsLong() - keys.loadedAt() < REFRESH_MIN_NANOS) {
            return false;
        }
        return matches(load(name).raw(), data, signature);
    }

    /** Acepta la firma etiquetada y la cruda (se prueba contra cada version habilitada). */
    private static boolean matches(Map<Integer, byte[]> keys, byte[] data, byte[] signature) {
        if (signature != null && new String(signature, StandardCharsets.UTF_8).startsWith(LABEL)) {
            return Ed25519Verifier.verify(keys, data, signature);
        }
        for (Map.Entry<Integer, byte[]> key : keys.entrySet()) {
            if (Ed25519Verifier.verify(Map.of(key.getKey(), key.getValue()), data, signature)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Map<Integer, byte[]>> publicKeys(TenantId tenantId, String keyId) {
        Map<Integer, byte[]> copy = new HashMap<>();
        keys(checked(tenantId, keyId)).raw().forEach((version, raw) -> copy.put(version, raw.clone()));
        return Optional.of(Map.copyOf(copy));
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

    private CachedKeys keys(String name) {
        CachedKeys cached = publicKeys.get(name);
        if (cached != null && nanos.getAsLong() - cached.loadedAt() < KEY_TTL_NANOS) {
            return cached;
        }
        return load(name);
    }

    private CachedKeys load(String name) {
        long now = nanos.getAsLong();
        Map<Integer, byte[]> spki = call(() -> api.publicKeys(name));
        if (spki.isEmpty()) {
            throw new KeyServiceUnavailableException("Cloud KMS sin versiones de firma habilitadas");
        }
        Map<Integer, byte[]> raw = new HashMap<>();
        call(() -> {
            spki.forEach((version, der) -> raw.put(version, rawEd25519(der)));
            return null;
        });
        CachedKeys fresh = new CachedKeys(Map.copyOf(raw), now);
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

    /** Las excepciones del puerto ya son seguras de propagar; el resto se reduce al nombre de su clase. */
    private static <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (KeyNotFoundException | KeyDisabledException | KeyServiceUnavailableException
                 | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KeyServiceUnavailableException("Cloud KMS no disponible: " + e.getClass().getSimpleName());
        }
    }
}
