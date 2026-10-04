package com.idp.security;

/** Fuente de verdad de las asignaciones de rol (tabla role_assignment de la base de control). */
public interface RoleAssignmentSource {

    boolean hasRole(String tenantId, String userId, String role);
}
