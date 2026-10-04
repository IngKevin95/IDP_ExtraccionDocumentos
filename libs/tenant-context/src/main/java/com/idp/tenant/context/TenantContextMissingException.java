package com.idp.tenant.context;

/** Acceso a BD sin tenant en el contexto de seguridad (silo-tenant AC-02). */
public class TenantContextMissingException extends SecurityException {
    private static final long serialVersionUID = 1L;

    public TenantContextMissingException() {
        super("Acceso a base de datos sin contexto de tenant");
    }
}
