package com.idp.chat.service;

import com.idp.security.Roles;
import java.util.Set;

/** Identidad revalidada del request: tenant y usuario del JWT y roles vigentes en role_assignment. */
public record Caller(String tenantId, String userId, Set<String> roles) {

    public boolean has(String role) {
        return roles.contains(role);
    }

    /** Ve documentos Altamente Confidenciales de otros cargadores (misma regla que document-service, SEC-018). */
    public boolean privileged() {
        return has(Roles.DATA_STEWARD) || has(Roles.TENANT_ADMIN) || has(Roles.SOPORTE);
    }
}
