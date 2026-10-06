package com.idp.quality.api;

import com.idp.security.TenantAuthorizer;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Revalida tenant y rol del JWT contra role_assignment en cada request (SEC-002, SEC-005). El tenant sale siempre del
 * claim del token, nunca del path, query ni body.
 */
@Component
public class CallerResolver {

    /** Llamador autorizado: tenant del token y sujeto. */
    public record Caller(UUID tenantId, String userId) {
    }

    private final TenantAuthorizer authorizer;

    public CallerResolver(TenantAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /** Devuelve el llamador si tiene al menos uno de los roles; si no, AccessDeniedException (403). */
    public Caller require(Jwt jwt, String... anyOf) {
        if (jwt == null) {
            throw new AccessDeniedException("Sin token");
        }
        String claim = jwt.getClaimAsString(TenantAuthorizer.TENANT_CLAIM);
        UUID tenant = parse(claim);
        String user = jwt.getSubject();
        if (tenant == null || !tenant.toString().equals(claim) || user == null || user.isBlank()) {
            throw new AccessDeniedException("Token sin tenant o sujeto validos");
        }
        for (String role : anyOf) {
            if (authorizer.authorize(jwt, role)) {
                return new Caller(tenant, user);
            }
        }
        throw new AccessDeniedException("Rol insuficiente");
    }

    private static UUID parse(String claim) {
        if (claim == null) {
            return null;
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
