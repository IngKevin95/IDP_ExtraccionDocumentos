package com.idp.document.service;

import com.idp.security.Roles;
import com.idp.security.TenantAuthorizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Revalida tenant y roles del JWT contra role_assignment en cada request (SEC-002). El tenant sale
 * siempre del claim del token, nunca del path ni del body.
 */
@Component
public class CallerResolver {

    private static final String[] ROLES = {Roles.OPERADOR, Roles.DATA_STEWARD, Roles.TENANT_ADMIN};

    private final TenantAuthorizer authorizer;

    public CallerResolver(TenantAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /** Devuelve el llamador si tiene al menos uno de los roles requeridos; si no, AccessDeniedException. */
    public Caller require(Jwt jwt, String... anyOf) {
        if (jwt == null) {
            throw new AccessDeniedException("Sin token");
        }
        String claim = jwt.getClaimAsString(TenantAuthorizer.TENANT_CLAIM);
        String tenant = normalizedTenant(claim);
        String user = jwt.getSubject();
        if (tenant == null || !tenant.equals(claim) || user == null || user.isBlank()) {
            throw new AccessDeniedException("Token sin tenant o sujeto validos");
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String role : ROLES) {
            if (authorizer.authorize(jwt, role)) {
                granted.add(role);
            }
        }
        for (String needed : anyOf) {
            if (granted.contains(needed)) {
                return new Caller(tenant, user, Set.copyOf(granted));
            }
        }
        throw new AccessDeniedException("Rol insuficiente");
    }

    public static String normalizedTenant(String claim) {
        if (claim == null) {
            return null;
        }
        try {
            return UUID.fromString(claim).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
