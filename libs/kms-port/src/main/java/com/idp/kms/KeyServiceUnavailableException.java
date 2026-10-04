package com.idp.kms;

/** El proveedor KMS no esta disponible; el llamador debe abortar y responder 503 (llaves-cifrado AC-06). */
public class KeyServiceUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public KeyServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public KeyServiceUnavailableException(String message) {
        super(message);
    }
}
