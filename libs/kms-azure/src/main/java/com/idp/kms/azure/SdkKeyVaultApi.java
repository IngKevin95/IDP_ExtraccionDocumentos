package com.idp.kms.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpClient;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.idp.kms.KeyService;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link KeyVaultApi} sobre {@code KeyClient} y {@code CryptographyClient} del SDK de Azure.
 * <p>
 * Rotacion de la KEK: el cliente "vigente" (sin version) se renueva cada {@link #CURRENT_TTL_NANOS} para ver
 * versiones nuevas; los clientes por version son inmutables y se conservan.
 * </p>
 */
final class SdkKeyVaultApi implements KeyVaultApi {

    static final long CURRENT_TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(30);

    private record Current(CryptographyClient client, long createdNanos) { }

    private final KeyClient keyClient;
    /** (nombre, version o null para la vigente) -> cliente criptografico. */
    private final BiFunction<String, String, CryptographyClient> cryptoClientFactory;
    private final LongSupplier nanoClock;
    private final long currentTtlNanos;
    /** Falso solo con emulador: Lowkey Vault no devuelve en deletedkeys una llave deshabilitada antes de borrarla. */
    private final boolean waitForDelete;
    private final Map<String, Current> currentClients = new ConcurrentHashMap<>();
    private final Map<String, CryptographyClient> versionedClients = new ConcurrentHashMap<>();

    SdkKeyVaultApi(KeyClient keyClient, BiFunction<String, String, CryptographyClient> cryptoClientFactory) {
        this(keyClient, cryptoClientFactory, System::nanoTime, CURRENT_TTL_NANOS, true);
    }

    SdkKeyVaultApi(KeyClient keyClient, BiFunction<String, String, CryptographyClient> cryptoClientFactory,
                   LongSupplier nanoClock, long currentTtlNanos, boolean waitForDelete) {
        this.keyClient = keyClient;
        this.cryptoClientFactory = cryptoClientFactory;
        this.nanoClock = nanoClock;
        this.currentTtlNanos = currentTtlNanos;
        this.waitForDelete = waitForDelete;
    }

    /**
     * @param httpClient cliente HTTP propio; null usa el de azure-core (Netty)
     * @param emulator desactiva la verificacion del recurso del challenge (los emuladores no sirven el dominio de Azure)
     */
    static SdkKeyVaultApi create(String vaultUrl, TokenCredential credential, HttpClient httpClient,
                                 boolean emulator) {
        String base = vaultUrl.endsWith("/") ? vaultUrl.substring(0, vaultUrl.length() - 1) : vaultUrl;
        KeyClientBuilder keys = new KeyClientBuilder().vaultUrl(base).credential(credential);
        if (httpClient != null) {
            keys.httpClient(httpClient);
        }
        if (emulator) {
            keys.disableChallengeResourceVerification();
        }
        KeyClient keyClient = keys.buildClient();
        return new SdkKeyVaultApi(keyClient, (name, version) -> {
            // Key Vault acepta el identificador sin version (usa la vigente); los emuladores exigen la version.
            String id = version != null ? base + "/keys/" + name + "/" + version
                : emulator ? keyClient.getKey(name).getId() : base + "/keys/" + name;
            CryptographyClientBuilder b = new CryptographyClientBuilder().keyIdentifier(id).credential(credential);
            if (httpClient != null) {
                b.httpClient(httpClient);
            }
            if (emulator) {
                b.disableChallengeResourceVerification();
            }
            return b.buildClient();
        }, System::nanoTime, CURRENT_TTL_NANOS, !emulator);
    }

    private CryptographyClient current(String keyName) {
        long now = nanoClock.getAsLong();
        return currentClients.compute(keyName, (k, old) ->
            old != null && now - old.createdNanos() < currentTtlNanos
                ? old : new Current(cryptoClientFactory.apply(k, null), now)).client();
    }

    private CryptographyClient versioned(String keyName, String version) {
        return versionedClients.computeIfAbsent(keyName + "/" + version, k -> cryptoClientFactory.apply(keyName, version));
    }

    @Override
    public Wrapped wrap(String keyName, byte[] material) {
        return call(() -> {
            WrapResult r = current(keyName).wrapKey(KeyWrapAlgorithm.RSA_OAEP_256, material);
            return new Wrapped(r.getEncryptedKey(), versionOf(r.getKeyId()));
        }, false);
    }

    @Override
    public byte[] unwrap(String keyName, String version, byte[] wrapped) {
        return call(() -> versioned(keyName, version).unwrapKey(KeyWrapAlgorithm.RSA_OAEP_256, wrapped).getKey(), true);
    }

    @Override
    public byte[] sign(String keyName, byte[] data) {
        return call(() -> current(keyName).signData(SignatureAlgorithm.ES256, data).getSignature(), false);
    }

    @Override
    public boolean verify(String keyName, byte[] data, byte[] signature) {
        return call(() -> Boolean.TRUE.equals(
            current(keyName).verifyData(SignatureAlgorithm.ES256, data, signature).isValid()), false);
    }

    @Override
    public void disable(String keyName) {
        try {
            call(() -> {
                KeyProperties props = keyClient.getKey(keyName).getProperties();
                keyClient.updateKeyProperties(props.setEnabled(false));
                return null;
            }, false);
            call(() -> {
                var poller = keyClient.beginDeleteKey(keyName);
                if (waitForDelete) {
                    poller.waitForCompletion(DELETE_TIMEOUT);
                }
                return null;
            }, false);
        } catch (KeyService.KeyNotFoundException | KeyService.KeyDisabledException yaAplicado) {
            // Crypto-shredding idempotente: la llave ya no existe o ya esta eliminada/deshabilitada.
            return;
        }
    }

    /** La version es el ultimo segmento del identificador {@code https://vault/keys/<nombre>/<version>}. */
    static String versionOf(String keyId) {
        String version = keyId == null ? "" : keyId.substring(keyId.lastIndexOf('/') + 1);
        if (!version.matches("[A-Za-z0-9]{1,64}")) {
            throw new IllegalStateException("Identificador de llave sin version");
        }
        return version;
    }

    private static <T> T call(Supplier<T> op, boolean unwrap) {
        try {
            return op.get();
        } catch (HttpResponseException e) {
            int status = e.getResponse() == null ? 0 : e.getResponse().getStatusCode();
            String text = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (status == 404) {
                throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
            }
            if ((status == 403 || status == 409) && (text.contains("disabled") || text.contains("deleted"))) {
                throw new KeyService.KeyDisabledException("KEK deshabilitada");
            }
            if (unwrap && status == 400) {
                throw new CiphertextRejectedException();
            }
            throw e;
        }
    }
}
