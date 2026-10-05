package com.idp.kms;

import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * KeyService sobre el motor transit de OpenBao/Vault, sin SDK (RestClient de Spring).
 * <ul>
 *   <li>wrap/unwrap de DEK con la KEK {@code t-<tenant>-<kekId>}; el AAD viaja como {@code context}
 *       (la llave transit debe crearse con derived=true).</li>
 *   <li>sign/verify con llaves ed25519.</li>
 *   <li>disableKek: marca la KEK como deshabilitada de inmediato en este proceso y la destruye en
 *       transit (deletion_allowed + DELETE), lo que es irreversible. El llamador es responsable de
 *       invocarlo al cierre de la ventana de retencion y de respetar el legal hold (SEC-016/017).</li>
 * </ul>
 * Cualquier fallo del proveedor se reporta como {@link KeyServiceUnavailableException} (AC-06).
 */
public final class OpenBaoTransitKeyService implements KeyService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() { };
    private static final String NAME_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final RestClient client;
    private final Supplier<String> tokenSupplier;
    private final String mount;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    public OpenBaoTransitKeyService(RestClient.Builder builder, String address, Supplier<String> tokenSupplier,
                                    String transitMount, boolean devMode, javax.net.ssl.SSLContext sslContext) {
        if (!devMode && address != null && !address.startsWith("https://")) {
            throw new IllegalArgumentException("HTTPS es obligatorio para OpenBao salvo en dev-mode");
        }
        if (sslContext != null) {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().sslContext(sslContext).build();
            builder.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient));
        }
        this.client = builder.baseUrl(Objects.requireNonNull(address)).build();
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier);
        this.mount = Objects.requireNonNull(transitMount);
    }

    private static String keyName(TenantId tenantId, String keyId) {
        if (!tenantId.value().matches(NAME_PATTERN) || keyId == null || !keyId.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("Identificador de tenant o llave invalido");
        }
        return "t-" + tenantId.value() + "-" + keyId;
    }

    private String checked(TenantId tenantId, String keyId) {
        String name = keyName(tenantId, keyId);
        if (disabled.contains(name)) {
            throw new KeyDisabledException("KEK deshabilitada");
        }
        return name;
    }

    @Override
    public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        Map<String, Object> body = new HashMap<>();
        body.put("plaintext", b64(dek));
        addContext(body, aadContext);
        String ciphertext = (String) data(call("POST", "/encrypt/" + name, body), "ciphertext");
        return new CryptoResult(ciphertext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId,
                                  Map<String, String> aadContext) {
        String name = checked(tenantId, kekId);
        Map<String, Object> body = new HashMap<>();
        body.put("ciphertext", new String(wrappedDek, StandardCharsets.UTF_8));
        addContext(body, aadContext);
        String plaintext = (String) data(call("POST", "/decrypt/" + name, body), "plaintext");
        byte[] dek = Base64.getDecoder().decode(plaintext);
        try {
            return new CryptoResult(dek);
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
        String name = checked(tenantId, keyId);
        String signature = (String) data(call("POST", "/sign/" + name, Map.of("input", b64(data))), "signature");
        return new CryptoResult(signature.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
        String name = checked(tenantId, keyId);
        Map<String, Object> body = Map.of("input", b64(data),
            "signature", new String(signature, StandardCharsets.UTF_8));
        return Boolean.TRUE.equals(data(call("POST", "/verify/" + name, body), "valid"));
    }

    @Override
    public void disableKek(TenantId tenantId, String kekId) {
        String name = keyName(tenantId, kekId);
        disabled.add(name);
        call("POST", "/keys/" + name + "/config", Map.of("deletion_allowed", true));
        call("DELETE", "/keys/" + name, null);
    }

    private static void addContext(Map<String, Object> body, Map<String, String> aad) {
        byte[] canonical = AadContext.canonical(aad);
        if (canonical.length > 0) {
            body.put("context", b64(canonical));
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Object data(Map<String, Object> response, String field) {
        Object d = response == null ? null : response.get("data");
        if (!(d instanceof Map<?, ?> m) || m.get(field) == null) {
            throw new KeyServiceUnavailableException("Respuesta inesperada del KMS");
        }
        return m.get(field);
    }

    private Map<String, Object> call(String method, String path, Map<String, Object> body) {
        String uri = "/v1/" + mount + path;
        try {
            RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(uri).header("X-Vault-Token", tokenSupplier.get());
            RestClient.ResponseSpec resp = (body == null ? spec : spec.body(body)).retrieve();
            return resp.body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new KeyNotFoundException("Llave inexistente en el KMS");
            }
            throw new KeyServiceUnavailableException("KMS respondio " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new KeyServiceUnavailableException("KMS no disponible", e);
        }
    }
}
