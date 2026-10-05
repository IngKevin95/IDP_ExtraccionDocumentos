package com.idp.tenant.infrastructure.platform;

import java.util.UUID;

/**
 * Puerto de aprovisionamiento declarativo del silo de un tenant (SEC-001, SEC-015). Cada paso de creacion
 * tiene su compensacion; el orquestador las ejecuta en orden inverso ante un fallo.
 */
public interface ProvisioningPort {
    record KeyIds(String dataKekId, String auditKekId) {}

    /** Reason codes de tenant.aprovisionamiento_fallido. */
    enum Reason { INFRASTRUCTURE_ERROR, CONFIGURATION_INVALID, TIMEOUT }

    class ProvisioningException extends RuntimeException {
        private final Reason reason;

        public ProvisioningException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    String createDatabase(UUID tenantId);

    void dropDatabase(UUID tenantId);

    String createBucket(UUID tenantId);

    void deleteBucket(UUID tenantId);

    KeyIds createKeys(UUID tenantId);

    void scheduleKeysDeletion(UUID tenantId, KeyIds keys);

    String createOpenBaoRole(UUID tenantId);

    void deleteOpenBaoRole(UUID tenantId);
}
