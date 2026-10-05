package com.idp.tenant.domain;

import com.idp.security.Roles;

/**
 * Roles asignables en un tenant; los nombres son los canonicos de {@link Roles}. BREAK_GLASS (soporte) solo se otorga
 * por el flujo excepcional de plataforma.
 */
public enum TenantRole {
    TENANT_ADMIN, OPERADOR, REVISOR, DATA_STEWARD, AUDITOR, OFICIAL_SEGURIDAD, COMPLIANCE, RIESGO_MODELO, BREAK_GLASS;

    /** Nombre canonico con el que se guarda en role_assignment. */
    public String canonical() {
        return name();
    }

    static {
        for (TenantRole r : values()) {
            if (!Roles.TENANT_ROLES.contains(r.name())) {
                throw new IllegalStateException("Rol no canonico: " + r.name());
            }
        }
    }
}
