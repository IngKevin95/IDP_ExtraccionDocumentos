package com.idp.kms.azure;

/** El proveedor rechazo el texto cifrado (400 en unwrapKey): manipulado, truncado o de otra llave. */
final class CiphertextRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CiphertextRejectedException() {
        super("Texto cifrado rechazado por el KMS");
    }
}
