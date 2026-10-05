package com.idp.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Coincide con las peticiones que NO llevan credenciales ambientales, es decir, las que un navegador no puede
 * adjuntar por su cuenta en un ataque CSRF.
 *
 * <p>CSRF solo es explotable cuando el navegador envia credenciales automaticamente (cookies o autenticacion
 * Basic). Estas APIs solo aceptan {@code Authorization: Bearer}, que el navegador no adjunta solo, de modo que
 * esas peticiones y las anonimas se excluyen de la comprobacion CSRF (las anonimas siguen recibiendo 401).
 * Cualquier peticion con cookie o Basic conserva la proteccion CSRF activa. Reemplaza al
 * {@code csrf.disable()} global.
 */
public final class NoAmbientCredentialsRequestMatcher implements RequestMatcher {

    private static final String BEARER = "Bearer ";
    private static final String BASIC = "Basic ";

    @Override
    public boolean matches(HttpServletRequest request) {
        return isIgnorable(request.getHeader(HttpHeaders.AUTHORIZATION), request.getHeader(HttpHeaders.COOKIE));
    }

    /** Misma regla para pilas reactivas, que no comparten la clase de request. */
    public static boolean isIgnorable(String authorization, String cookie) {
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return true;
        }
        boolean basic = authorization != null && authorization.regionMatches(true, 0, BASIC, 0, BASIC.length());
        return !basic && (cookie == null || cookie.isBlank());
    }
}
