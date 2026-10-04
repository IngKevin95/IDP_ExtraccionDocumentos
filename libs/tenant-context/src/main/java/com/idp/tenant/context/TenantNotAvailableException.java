package com.idp.tenant.context;

/** Tenant inexistente, suspendido o sin credenciales disponibles (silo-tenant AC-08). */
public class TenantNotAvailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TenantNotAvailableException(String message) {
        super(message);
    }

    public TenantNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
