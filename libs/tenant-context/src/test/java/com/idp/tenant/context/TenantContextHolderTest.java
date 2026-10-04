package com.idp.tenant.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

    @Test
    void shouldStoreAndRetrieveTenantId() {
        TenantContextHolder.setTenantId("T1");
        assertEquals("T1", TenantContextHolder.getTenantId());
        TenantContextHolder.clear();
        assertNull(TenantContextHolder.getTenantId());
    }
}
