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
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.idp.kms.KeyService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** {@link KeyVaultApi} sobre {@code KeyClient} y {@code CryptographyClient} del SDK de Azure. */
final class SdkKeyVaultApi implements KeyVaultApi {

    private final KeyClient keyClient;
    private final Function<String, CryptographyClient> cryptoClientFactory;
    private final Map<String, CryptographyClient> cryptoClients = new ConcurrentHashMap<>();

    SdkKeyVaultApi(KeyClient keyClient, Function<String, CryptographyClient> cryptoClientFactory) {
        this.keyClient = keyClient;
        this.cryptoClientFactory = cryptoClientFactory;
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
        return new SdkKeyVaultApi(keyClient, name -> {
            // Key Vault acepta el identificador sin version (usa la vigente); los emuladores exigen la version.
            String id = emulator ? keyClient.getKey(name).getId() : base + "/keys/" + name;
            CryptographyClientBuilder b = new CryptographyClientBuilder().keyIdentifier(id).credential(credential);
            if (httpClient != null) {
                b.httpClient(httpClient);
            }
            if (emulator) {
                b.disableChallengeResourceVerification();
            }
            return b.buildClient();
        });
    }

    private CryptographyClient crypto(String keyName) {
        return cryptoClients.computeIfAbsent(keyName, cryptoClientFactory);
    }

    @Override
    public byte[] wrap(String keyName, byte[] material) {
        return mapNotFound(() -> crypto(keyName).wrapKey(KeyWrapAlgorithm.RSA_OAEP_256, material).getEncryptedKey());
    }

    @Override
    public byte[] unwrap(String keyName, byte[] wrapped) {
        return mapNotFound(() -> crypto(keyName).unwrapKey(KeyWrapAlgorithm.RSA_OAEP_256, wrapped).getKey());
    }

    @Override
    public byte[] sign(String keyName, byte[] data) {
        return mapNotFound(() -> crypto(keyName).signData(SignatureAlgorithm.ES256, data).getSignature());
    }

    @Override
    public boolean verify(String keyName, byte[] data, byte[] signature) {
        return mapNotFound(() -> Boolean.TRUE.equals(
            crypto(keyName).verifyData(SignatureAlgorithm.ES256, data, signature).isValid()));
    }

    @Override
    public void disable(String keyName) {
        mapNotFound(() -> {
            KeyProperties props = keyClient.getKey(keyName).getProperties();
            keyClient.updateKeyProperties(props.setEnabled(false));
            keyClient.beginDeleteKey(keyName);
            return null;
        });
    }

    private static <T> T mapNotFound(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpResponseException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) {
                throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
            }
            throw e;
        }
    }
}
