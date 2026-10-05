package com.idp.audit.security;

import com.idp.security.TenantAuthorizer;
import com.idp.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalida en cada request /v1/audit/** (salvo la verificacion publica): el claim tenant_id debe ser un UUID y
 * el rol AUDITOR debe seguir vigente en role_assignment para ese tenant (el JWT solo prueba identidad). Fija
 * el tenant en {@link TenantContextHolder}; los controladores lo leen del token, nunca del path.
 */
public class AuditorRevalidationFilter extends OncePerRequestFilter {

    private final TenantAuthorizer authorizer;

    public AuditorRevalidationFilter(TenantAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/v1/audit/") || uri.startsWith(SecurityConfig.PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String claim = jwt.getToken().getClaimAsString(TenantAuthorizer.TENANT_CLAIM);
        UUID tenantId;
        try {
            tenantId = claim == null ? null : UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            tenantId = null;
        }
        if (tenantId == null || !authorizer.authorize(jwt.getToken(), SecurityConfig.AUDITOR)) {
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
        response.setContentType("application/json");
        response.getOutputStream().write(("{\"code\":\"AUDIT_FORBIDDEN\",\"message\":\"Acceso denegado\","
                + "\"timestamp\":\"" + Instant.now() + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
