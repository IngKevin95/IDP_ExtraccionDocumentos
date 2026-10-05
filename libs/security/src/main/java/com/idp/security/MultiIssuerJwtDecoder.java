package com.idp.security;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Elige el decodificador por el claim {@code iss} (sin confiar en el contenido: cada decodificador valida firma e
 * issuer con su propia llave). Un issuer desconocido se rechaza (A3: emisor de plataforma distinto del de tenant).
 */
public final class MultiIssuerJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> byIssuer;

    public MultiIssuerJwtDecoder(Map<String, JwtDecoder> byIssuer) {
        this.byIssuer = Map.copyOf(byIssuer);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("Token malformado");
        }
        JwtDecoder decoder = issuer == null ? null : byIssuer.get(issuer);
        if (decoder == null) {
            throw new BadJwtException("Issuer no confiable");
        }
        return decoder.decode(token);
    }
}
