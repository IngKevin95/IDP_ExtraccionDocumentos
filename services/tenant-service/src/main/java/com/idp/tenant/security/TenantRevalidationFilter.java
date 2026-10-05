package com.idp.tenant.security;

import com.idp.security.TenantAuthorizer;
import com.idp.tenant.context.TenantContextHolder;
import com.idp.tenant.domain.Tenant;
import com.idp.security.Roles;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalida en cada request /v1/tenant/**: tenant del claim tenant_id existente y ACTIVE, y rol TENANT_ADMIN
 * vigente en role_assignment (el JWT solo prueba identidad). Fija el tenant en {@link TenantContextHolder}.
 */
public class TenantRevalidationFilter extends OncePerRequestFilter {
    private final TenantAuthorizer authorizer;
    private final TenantRepository tenants;

    public TenantRevalidationFilter(TenantAuthorizer authorizer, TenantRepository tenants) {
        this.authorizer = authorizer;
        this.tenants = tenants;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/tenant/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String claim = jwt.getToken().getClaimAsString("tenant_id");
        if (claim == null) {
            deny(response);
            return;
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            deny(response);
            return;
        }
        Tenant tenant = tenants.find(tenantId).orElse(null);
        if (tenant == null || tenant.status() != TenantStatus.ACTIVE
                || !authorizer.authorize(tenantId.toString(), jwt.getName(), Roles.TENANT_ADMIN)) {
            deny(response);
            return;
        }
        TenantContextHolder.setTenantId(tenantId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private static void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getOutputStream().write(
                "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Acceso denegado\"}"
                        .getBytes(StandardCharsets.UTF_8));
    }
}
