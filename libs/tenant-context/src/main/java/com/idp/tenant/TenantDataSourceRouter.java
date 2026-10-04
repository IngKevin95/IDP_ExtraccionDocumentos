package com.idp.tenant;

public interface TenantDataSourceRouter {
    String getDataSourceForTenant(TenantId tenantId);
}
