package com.idp.security;

public class TenantAuthorizer {
    private final RoleAssignmentVerifier verifier;

    public TenantAuthorizer(RoleAssignmentVerifier verifier) {
        this.verifier = verifier;
    }

    public boolean authorize(String tenantId, String userId, String role) {
        return verifier.hasRole(tenantId, userId, role);
    }
}
