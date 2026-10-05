package com.idp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idp.tenant.context.TenantContextHolder;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TenantAuthorizerTest {

    private final RoleAssignmentVerifier verifier = mock(RoleAssignmentVerifier.class);
    private final TenantAuthorizer authorizer = new TenantAuthorizer(verifier);

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    private static Jwt jwt(String tenant, String sub) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
        if (tenant != null) {
            b.claim("tenant_id", tenant);
        }
        if (sub != null) {
            b.subject(sub);
        }
        return b.build();
    }

    @Test
    void ac01_tenantDelJwtSeCotejaConRoleAssignment() {
        when(verifier.hasRole("t1", "u1", "ANALISTA")).thenReturn(true);
        assertTrue(authorizer.authorize(jwt("t1", "u1"), "ANALISTA"));
        assertFalse(authorizer.authorize(jwt("t1", "u1"), "ADMIN"));
    }

    @Test
    void tokenSinTenantOSinSubjectSeDeniega() {
        assertFalse(authorizer.authorize(jwt(null, "u1"), "R"));
        assertFalse(authorizer.authorize(jwt("t1", null), "R"));
        assertFalse(authorizer.authorize((org.springframework.security.oauth2.jwt.Jwt) null, "R"));
        verify(verifier, never()).hasRole(any(), any(), any());
    }

    @Test
    void ac07_tenantDelContextoDistintoAlDelJwtSeDeniega() {
        TenantContextHolder.setTenantId("t2");
        when(verifier.hasRole("t1", "u1", "R")).thenReturn(true);
        assertFalse(authorizer.authorize(jwt("t1", "u1"), "R"));
        verify(verifier, never()).hasRole(any(), any(), any());
    }

    @Test
    void contextoIgualAlJwtPermiteLaVerificacion() {
        TenantContextHolder.setTenantId("t1");
        when(verifier.hasRole("t1", "u1", "R")).thenReturn(true);
        assertTrue(authorizer.authorize(jwt("t1", "u1"), "R"));
    }
}
