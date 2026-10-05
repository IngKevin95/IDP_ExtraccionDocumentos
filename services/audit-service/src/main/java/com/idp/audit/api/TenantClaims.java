package com.idp.audit.api;

import com.idp.security.TenantAuthorizer;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Tenant y usuario del JWT ya revalidado por el filtro de seguridad. */
final class TenantClaims {
    private TenantClaims() {}

    static UUID tenantId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString(TenantAuthorizer.TENANT_CLAIM);
            if (claim != null) {
                return UUID.fromString(claim);
            }
        }
        throw new IllegalStateException("JWT sin tenant_id: el filtro de revalidacion debio rechazarlo");
    }
}
