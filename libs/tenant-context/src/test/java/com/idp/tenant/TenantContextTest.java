package com.idp.tenant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {
    @Test
    void testTenantContext() {
        TenantId id = new TenantId("tenant1");
        TenantContext.setTenantId(id);
        assertEquals("tenant1", TenantContext.getTenantId().value());
        TenantContext.clear();
        assertNull(TenantContext.getTenantId());
    }
}
