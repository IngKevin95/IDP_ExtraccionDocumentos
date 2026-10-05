package com.idp.extraction.store;

/** Intento de leer o escribir fuera del silo del tenant en contexto: debe fallar ruidosamente (RNF-101). */
public class TenantIsolationException extends IllegalStateException {
    public TenantIsolationException(String message) {
        super(message);
    }
}
