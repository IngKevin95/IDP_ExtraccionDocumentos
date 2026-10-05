package com.idp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** A2/A3: issuer obligatorio, audiencia por servicio y emisores separados (tenant y plataforma). */
class IdpJwtConfigurationTest {

    private static final String TENANT_ISS = "https://idp.test/tenants";
    private static final String PLATFORM_ISS = "https://idp.test/platform";

    private static Jwt jwt(List<String> aud) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (aud != null) {
            b.audience(aud);
        }
        return b.build();
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String token(KeyPair kp, String issuer, String audience) throws Exception {
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().subject("u").issuer(issuer)
            .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))));
        if (audience != null) {
            c.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) kp.getPrivate()));
        return jwt.serialize();
    }

    private static JwtDecoder decoder(KeyPair kp, String issuer, String audience) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) kp.getPublic()).build();
        d.setJwtValidator(IdpJwtConfiguration.validators(issuer, audience));
        return d;
    }

    @Test
    void sinIssuerOSinAudienciaElArranqueFalla() {
        assertThrows(IllegalStateException.class,
            () -> IdpJwtConfiguration.decoder("http://localhost:0/jwks", "", "document-service"));
        assertThrows(IllegalStateException.class,
            () -> IdpJwtConfiguration.decoder("http://localhost:0/jwks", null, "document-service"));
        assertThrows(IllegalArgumentException.class,
            () -> IdpJwtConfiguration.decoder("http://localhost:0/jwks", TENANT_ISS, " "));
        assertThrows(IllegalStateException.class,
            () -> IdpJwtConfiguration.decoder("", TENANT_ISS, "document-service"));
    }

    @Test
    void emisorDePlataformeIgualAlDeTenantsSeRechaza() {
        assertThrows(IllegalStateException.class, () -> new IdpJwtConfiguration().idpJwtDecoder(
            "http://localhost:0/jwks", TENANT_ISS, "tenant-service", TENANT_ISS, ""));
    }

    @Test
    void audienciaDebeContenerLaDelServicio() {
        AudienceValidator v = new AudienceValidator("document-service");
        assertTrue(v.validate(jwt(List.of("otro", "document-service"))).getErrors().isEmpty());
        assertFalse(v.validate(jwt(List.of("audit-service"))).getErrors().isEmpty());
        assertFalse(v.validate(jwt(null)).getErrors().isEmpty());
    }

    @Test
    void decodificadorValidaIssuerYAudiencia() throws Exception {
        KeyPair kp = keys();
        JwtDecoder d = decoder(kp, TENANT_ISS, "document-service");
        assertTrue(d.decode(token(kp, TENANT_ISS, "document-service")).getAudience().contains("document-service"));
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class,
            () -> d.decode(token(kp, "https://otro.test", "document-service")));
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class,
            () -> d.decode(token(kp, TENANT_ISS, "audit-service")));
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class,
            () -> d.decode(token(kp, TENANT_ISS, null)));
    }

    @Test
    void multiEmisorEligePorIssuerYRechazaDesconocidos() throws Exception {
        KeyPair tenantKeys = keys();
        KeyPair platformKeys = keys();
        MultiIssuerJwtDecoder multi = new MultiIssuerJwtDecoder(Map.of(
            TENANT_ISS, decoder(tenantKeys, TENANT_ISS, "tenant-service"),
            PLATFORM_ISS, decoder(platformKeys, PLATFORM_ISS, "tenant-service")));

        assertEquals(TENANT_ISS, multi.decode(token(tenantKeys, TENANT_ISS, "tenant-service")).getIssuer().toString());
        assertEquals(PLATFORM_ISS,
            multi.decode(token(platformKeys, PLATFORM_ISS, "tenant-service")).getIssuer().toString());
        // el issuer de plataforma firmado con la llave de tenant no se acepta
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class,
            () -> multi.decode(token(tenantKeys, PLATFORM_ISS, "tenant-service")));
        assertThrows(BadJwtException.class, () -> multi.decode(token(tenantKeys, "https://otro.test", "x")));
        assertThrows(BadJwtException.class, () -> multi.decode("no-es-un-jwt"));
    }
}
