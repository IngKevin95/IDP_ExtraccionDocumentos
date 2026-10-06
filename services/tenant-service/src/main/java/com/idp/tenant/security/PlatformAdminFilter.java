package com.idp.tenant.security;

import com.idp.tenant.infrastructure.persistence.PlatformAdminRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A3: en /v1/admin/** no basta el claim PLATFORM_ADMIN. El token debe venir del emisor de plataforma configurado
 * ({@code idp.security.platform-issuer}) y su subject debe estar habilitado en la tabla platform_admin.
 */
public class PlatformAdminFilter extends OncePerRequestFilter {
    private final PlatformAdminRepository admins;
    private final String platformIssuer;

    public PlatformAdminFilter(PlatformAdminRepository admins, String platformIssuer) {
        this.admins = admins;
        this.platformIssuer = platformIssuer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        var tokenIssuer = jwt.getToken().getIssuer();
        String issuer = tokenIssuer == null ? null : tokenIssuer.toString();
        if (!platformIssuer.equals(issuer) || !admins.isActive(jwt.getName())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getOutputStream().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Acceso denegado\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
