package com.idp.notification.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validacion estatica de la URL destino (SEC-027, ADR 0017): solo HTTPS, sin credenciales embebidas, host en la
 * allowlist del tenant y sin literales IP ni nombres internos prohibidos. Se aplica al registrar y otra vez antes
 * de cada envio. No hace consultas DNS: la resolucion segura ocurre en {@link SsrfGuard} al conectar.
 */
public final class WebhookUrlPolicy {

    static final int MAX_URL_LENGTH = 2048;
    private static final Pattern DOTTED_QUAD = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern NUMERIC_LIKE = Pattern.compile("^(0[xX][0-9a-fA-F]+|\\d+)(\\.(0[xX][0-9a-fA-F]+|\\d+)){0,3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");
    private static final List<String> BLOCKED_SUFFIXES = List.of(".localhost", ".local", ".internal", ".localdomain",
        ".home.arpa", ".svc", ".cluster.local");
    private static final List<String> BLOCKED_NAMES = List.of("localhost", "metadata", "kubernetes");

    private final AddressPolicy addresses;
    private final boolean allowInsecureHttp;

    public WebhookUrlPolicy(AddressPolicy addresses, boolean allowInsecureHttp) {
        this.addresses = addresses;
        this.allowInsecureHttp = allowInsecureHttp;
    }

    /** Valida la URL y devuelve el URI; lanza {@link SsrfViolationException} con un codigo estable si no cumple. */
    public URI validate(String url, List<String> allowedHosts) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new SsrfViolationException("INVALID_URL", "URL ausente o demasiado larga");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new SsrfViolationException("INVALID_URL", "URL mal formada");
        }
        String host = uri.getHost();
        if (uri.getScheme() == null || host == null || uri.getUserInfo() != null || uri.getRawFragment() != null) {
            throw new SsrfViolationException("INVALID_URL", "URL sin esquema o host, con credenciales o fragmento");
        }
        String normalized = normalize(host);
        checkHostname(normalized);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        if (!https && !(allowInsecureHttp && "http".equals(scheme))) {
            throw new SsrfViolationException("INSECURE_SCHEME", "Solo se admite HTTPS");
        }
        if (!isAllowed(normalized, allowedHosts)) {
            throw new SsrfViolationException("HOST_NOT_ALLOWED", "Host fuera de la allowlist del tenant");
        }
        return uri;
    }

    private void checkHostname(String host) {
        if (host.startsWith("[")) {
            String literal = host.substring(1, host.length() - 1);
            if (!IPV6_CHARS.matcher(literal).matches()) {
                throw new SsrfViolationException("INVALID_URL", "Literal IPv6 no valido");
            }
            checkLiteral(literal);
            return;
        }
        if (DOTTED_QUAD.matcher(host).matches()) {
            checkLiteral(host);
            return;
        }
        if (NUMERIC_LIKE.matcher(host).matches()) {
            // Formas numericas no canonicas (decimal, octal, hex, abreviadas) que algunas pilas interpretan como IP.
            throw new SsrfViolationException("BLOCKED_ADDRESS", "Forma numerica de host no admitida");
        }
        for (String name : BLOCKED_NAMES) {
            if (host.equals(name)) {
                throw new SsrfViolationException("BLOCKED_HOSTNAME", "Nombre de host interno");
            }
        }
        for (String suffix : BLOCKED_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw new SsrfViolationException("BLOCKED_HOSTNAME", "Nombre de host interno");
            }
        }
    }

    private void checkLiteral(String literal) {
        try {
            // Literal numerico: InetAddress no consulta DNS.
            InetAddress a = InetAddress.getByName(literal);
            if (addresses.isBlocked(a)) {
                throw new SsrfViolationException("BLOCKED_ADDRESS", "Direccion IP prohibida");
            }
        } catch (UnknownHostException e) {
            throw new SsrfViolationException("INVALID_URL", "Literal IP no valido");
        }
    }

    private static String normalize(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".") && h.length() > 1) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /** Entrada exacta o comodin {@code *.dominio} (cualquier subdominio, no el apex). */
    static boolean isAllowed(String host, List<String> allowedHosts) {
        if (allowedHosts == null) {
            return false;
        }
        for (String entry : allowedHosts) {
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.startsWith("*.")) {
                String suffix = e.substring(1);
                if (host.length() > suffix.length() && host.endsWith(suffix)) {
                    return true;
                }
            } else if (e.equals(host)) {
                return true;
            }
        }
        return false;
    }
}
