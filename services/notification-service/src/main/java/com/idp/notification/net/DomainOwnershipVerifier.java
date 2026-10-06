package com.idp.notification.net;

/**
 * Puerto: demuestra que quien registra un host en la allowlist controla su dominio. Implementacion de produccion:
 * {@link DnsDomainOwnershipVerifier} (TXT DNS); los tests usan una falsa.
 */
public interface DomainOwnershipVerifier {

    /** Nombre del registro TXT donde se publica el desafio: {@code _idp-verify.<dominio>}. */
    static String challengeName(String domain) {
        return "_idp-verify." + domain;
    }

    /** Valor esperado del TXT para un token de desafio. */
    static String challengeValue(String token) {
        return "idp-verify=" + token;
    }

    /** true si el dominio (o un ancestro que no sea sufijo publico) publica el TXT con el valor del token. */
    boolean owns(String domain, String token);
}
