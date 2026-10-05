package com.idp.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JwtDecoder comun (A2/A3): issuer obligatorio (el arranque falla si falta), audiencia propia del servicio
 * ({@code idp.security.audience}) y, si se configura {@code idp.security.platform-issuer}, un segundo emisor
 * independiente para tokens de plataforma. Los servicios la importan con {@code @Import}.
 */
@Configuration(proxyBeanMethods = false)
public class IdpJwtConfiguration {

    /** Validadores de un token: ventana temporal, issuer exacto y audiencia del servicio. */
    public static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        requireText(issuer, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
            new AudienceValidator(audience));
    }

    public static JwtDecoder decoder(String jwkSetUri, String issuer, String audience) {
        requireText(jwkSetUri, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        d.setJwtValidator(validators(issuer, audience));
        return d;
    }

    @Bean
    JwtDecoder idpJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
            @Value("${idp.security.audience:}") String audience,
            @Value("${idp.security.platform-issuer:}") String platformIssuer,
            @Value("${idp.security.platform-jwk-set-uri:}") String platformJwkSetUri) {
        JwtDecoder tenantDecoder = decoder(jwkSetUri, issuer, audience);
        if (platformIssuer == null || platformIssuer.isBlank()) {
            return tenantDecoder;
        }
        if (platformIssuer.equals(issuer)) {
            throw new IllegalStateException("idp.security.platform-issuer debe ser distinto del issuer de tenants");
        }
        Map<String, JwtDecoder> byIssuer = new LinkedHashMap<>();
        byIssuer.put(issuer, tenantDecoder);
        byIssuer.put(platformIssuer, decoder(platformJwkSetUri.isBlank() ? jwkSetUri : platformJwkSetUri,
            platformIssuer, audience));
        return new MultiIssuerJwtDecoder(byIssuer);
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Propiedad obligatoria sin valor: " + property);
        }
    }

}
