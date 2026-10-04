package com.idp.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityTest {
    @Test
    void testAuth() {
        RoleAssignmentVerifier verifier = (t, u, r) -> true;
        TenantAuthorizer filter = new TenantAuthorizer(verifier);
        assertTrue(filter.authorize("t", "u", "r"));
    }
}
