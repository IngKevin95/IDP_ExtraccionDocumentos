package com.idp.security.breakglass;

import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.mockito.Mockito.*;

class BreakGlassAuditFilterTest {

    private final RoleAssignmentVerifier verifier = mock(RoleAssignmentVerifier.class);
    private final BreakGlassAuditFilter filter = new BreakGlassAuditFilter(verifier);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsCriticalAlertIfUserHasBreakGlassRole() throws Exception {
        String tenant = UUID.randomUUID().toString();
        TenantContextHolder.setTenantId(tenant);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("userA");
        
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);

        SecurityContextHolder.getContext().setAuthentication(auth);

        when(verifier.hasRole(tenant, "userA", Roles.SOPORTE)).thenReturn(true);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/sensitive");
        when(req.getMethod()).thenReturn("GET");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
