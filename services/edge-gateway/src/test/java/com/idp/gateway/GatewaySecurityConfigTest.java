package com.idp.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** A2: issuer y audiencia obligatorios en el borde. */
class GatewaySecurityConfigTest {

    private static Jwt jwt(List<String> aud) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (aud != null) {
            b.audience(aud);
        }
        return b.build();
    }

    @Test
    void sinIssuerOAudienciaElArranqueFalla() {
        assertThrows(IllegalStateException.class,
                () -> GatewaySecurityConfig.decoder("http://localhost:0/jwks", "", "edge-gateway"));
        assertThrows(IllegalStateException.class,
                () -> GatewaySecurityConfig.decoder("http://localhost:0/jwks", "https://idp.test/tenants", ""));
    }

    @Test
    void validadorDeAudienciaExigeLaAudienciaDelGateway() {
        var v = GatewaySecurityConfig.audienceValidator("edge-gateway");
        assertTrue(v.validate(jwt(List.of("otro", "edge-gateway"))).getErrors().isEmpty());
        assertFalse(v.validate(jwt(List.of("document-service"))).getErrors().isEmpty());
        assertFalse(v.validate(jwt(null)).getErrors().isEmpty());
    }
}
