package com.idp.security;

public interface RoleAssignmentVerifier {
    boolean hasRole(String tenantId, String userId, String role);
}
