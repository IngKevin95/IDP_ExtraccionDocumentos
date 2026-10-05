package com.idp.notification.net;

/** Destino prohibido por la politica anti-SSRF (SEC-027). El mensaje no incluye la IP resuelta. */
public final class SsrfViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public SsrfViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Codigo estable: BLOCKED_ADDRESS, BLOCKED_HOSTNAME, HOST_NOT_ALLOWED, INSECURE_SCHEME, INVALID_URL. */
    public String code() {
        return code;
    }
}
