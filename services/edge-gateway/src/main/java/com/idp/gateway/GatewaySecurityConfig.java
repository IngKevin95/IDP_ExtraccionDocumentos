package com.idp.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Resource Server JWT del borde (A2): el issuer es obligatorio (el arranque falla si falta) y el token debe llevar la
 * audiencia del gateway. Solo las sondas de salud son publicas.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
                                  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
                                  @Value("${idp.security.audience:}") String audience) {
        return decoder(jwkSetUri, issuer, audience);
    }

    static ReactiveJwtDecoder decoder(String jwkSetUri, String issuer, String audience) {
        require(jwkSetUri, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        require(issuer, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        require(audience, "idp.security.audience");
        NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator(audience)));
        return d;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Audiencia no valida", null));
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Propiedad obligatoria sin valor: " + property);
        }
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health",
                                "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> { }))
                .build();
    }
}
