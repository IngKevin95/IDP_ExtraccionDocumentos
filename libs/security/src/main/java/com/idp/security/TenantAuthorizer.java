package com.idp.security;

import com.idp.tenant.TenantContext;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public class TenantAuthorizer {

    public static final String TENANT_CLAIM = "tenant_id";

    private final RoleAssignmentVerifier verifier;

    public TenantAuthorizer(RoleAssignmentVerifier verifier) {
        this.verifier = verifier;
    }

    public boolean authorize(String tenantId, String userId, String role) {
        return verifier.hasRole(tenantId, userId, role);
    }

    /**
     * Autoriza comparando el tenant del JWT con role_assignment (SEC-002). El tenant se toma siempre
     * del claim del token; si hay un tenant ya fijado en el contexto y difiere, se deniega (AC-07).
     */
    public boolean authorize(Jwt jwt, String role) {
        if (jwt == null) {
            return false;
        }
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);
        String user = jwt.getSubject();
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank()) {
            return false;
        }
        String contextTenant = currentContextTenant();
        if (contextTenant != null && !contextTenant.equals(tenant)) {
            return false;
        }
        return verifier.hasRole(tenant, user, role);
    }

    private static String currentContextTenant() {
        String fromHolder = TenantContextHolder.getTenantId();
        if (fromHolder != null && !fromHolder.isBlank()) {
            return fromHolder;
        }
        TenantId fromContext = TenantContext.getTenantId();
        return fromContext == null ? null : fromContext.value();
    }
}
