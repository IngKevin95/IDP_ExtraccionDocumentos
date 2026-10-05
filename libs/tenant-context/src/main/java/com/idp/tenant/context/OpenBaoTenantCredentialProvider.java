package com.idp.tenant.context;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Adaptador OpenBao/Vault (motor database): GET {addr}/v1/{credsPathTemplate} devuelve
 * credenciales dinamicas con forma {data:{username,password}}. Sin SDK; usa RestClient de Spring.
 * El marcador {tenant} de las plantillas se reemplaza por el tenantId validado.
 */
public final class OpenBaoTenantCredentialProvider implements TenantCredentialProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() { };

    private final RestClient client;
    private final Supplier<String> tokenSupplier;
    private final String credsPathTemplate;
    private final String jdbcUrlTemplate;

    public OpenBaoTenantCredentialProvider(RestClient.Builder builder, String address,
                                           Supplier<String> tokenSupplier,
                                           String credsPathTemplate, String jdbcUrlTemplate,
                                           boolean devMode, javax.net.ssl.SSLContext sslContext) {
        if (!devMode && address != null && !address.startsWith("https://")) {
            throw new IllegalArgumentException("HTTPS es obligatorio para OpenBao salvo en dev-mode");
        }
        if (sslContext != null) {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().sslContext(sslContext).build();
            builder.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient));
        }
        this.client = builder.baseUrl(Objects.requireNonNull(address)).build();
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier);
        this.credsPathTemplate = Objects.requireNonNull(credsPathTemplate);
        this.jdbcUrlTemplate = Objects.requireNonNull(jdbcUrlTemplate);
    }

    @Override
    public TenantConnection resolve(String tenantId) {
        if (tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new TenantNotAvailableException("tenantId invalido");
        }
        String path = "/v1/" + credsPathTemplate.replace("{tenant}", tenantId);
        Map<String, Object> body;
        try {
            body = client.get().uri(path)
                .header("X-Vault-Token", tokenSupplier.get())
                .retrieve()
                .body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 403) {
                throw new TenantNotAvailableException("Tenant sin credenciales disponibles", e);
            }
            throw new TenantNotAvailableException("OpenBao respondio " + status, e);
        } catch (RestClientException e) {
            throw new TenantNotAvailableException("OpenBao no disponible", e);
        }
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof Map<?, ?> m) || m.get("username") == null || m.get("password") == null) {
            throw new TenantNotAvailableException("Respuesta de credenciales invalida");
        }
        return new TenantConnection(jdbcUrlTemplate.replace("{tenant}", tenantId),
            String.valueOf(m.get("username")), String.valueOf(m.get("password")));
    }
}
