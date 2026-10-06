package com.idp.security.breakglass;

import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class BreakGlassAuditFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassAuditFilter.class);
    private final RoleAssignmentVerifier verifier;

    public BreakGlassAuditFilter(RoleAssignmentVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = TenantContextHolder.getTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (tenantId != null && !tenantId.isBlank() && auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String userId = jwt.getSubject();
            if (userId != null && verifier.hasRole(tenantId, userId, Roles.SOPORTE)) {
                LOG.error("ALERTA CRITICA: Operacion bajo Break-glass del usuario {} en el recurso {} {}",
                        userId, request.getMethod(), request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }
}
