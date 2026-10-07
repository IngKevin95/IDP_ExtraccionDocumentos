package com.idp.kms;

import java.util.Locale;

public enum SignatureAlgorithm {
    ED25519,
    ES256;

    /** Valor del campo {@code algorithm} en los documentos firmados (minusculas, independiente del locale). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
