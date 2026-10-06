package com.idp.chat.service;

import com.idp.chat.service.Exceptions.AccessDeniedException;
import com.idp.chat.service.Exceptions.UnauthenticatedException;
import com.idp.security.Roles;
import com.idp.security.TenantAuthorizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Revalida tenant y roles del JWT contra role_assignment en cada request (SEC-002). Falla cerrado: sin token, sin
 * tenant UUID valido o sin sujeto no hay identidad. El break-glass (RN-06) llega como rol vigente en la misma tabla.
 */
@Component
public class CallerResolver {

    /** Roles que pueden consultar el chat documental (el acceso por documento se valida aparte). */
    private static final String[] ROLES = {Roles.OPERADOR, Roles.REVISOR, Roles.DATA_STEWARD, Roles.TENANT_ADMIN,
        Roles.SOPORTE};

    static final int MAX_USER_ID = 128;

    private final TenantAuthorizer authorizer;

    public CallerResolver(TenantAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    public Caller require(Jwt jwt) {
        if (jwt == null) {
            throw new UnauthenticatedException();
        }
        String claim = jwt.getClaimAsString(TenantAuthorizer.TENANT_CLAIM);
        String tenant = normalizedTenant(claim);
        String user = jwt.getSubject();
        if (tenant == null || !tenant.equals(claim) || user == null || user.isBlank() || user.length() > MAX_USER_ID) {
            throw new AccessDeniedException("Token sin tenant o sujeto validos");
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String role : ROLES) {
            if (authorizer.authorize(jwt, role)) {
                granted.add(role);
            }
        }
        if (granted.isEmpty()) {
            throw new AccessDeniedException("Rol insuficiente");
        }
        return new Caller(tenant, user, Set.copyOf(granted));
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
