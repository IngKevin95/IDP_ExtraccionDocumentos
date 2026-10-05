package com.idp.tenant.context;

/** Puerto para resolver credenciales efimeras de BD por tenant (SEC-043). */
public interface TenantCredentialProvider {

    /**
     * Resuelve la conexion al silo del tenant.
     *
     * @throws TenantNotAvailableException si el tenant no existe o esta suspendido
     */
    TenantConnection resolve(String tenantId);
}
