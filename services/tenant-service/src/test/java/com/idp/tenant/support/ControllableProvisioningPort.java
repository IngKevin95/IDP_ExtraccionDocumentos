package com.idp.tenant.support;

import com.idp.tenant.infrastructure.platform.DeclarativeProvisioningAdapter;
import java.util.UUID;

/** Adaptador declarativo con inyeccion de fallos para probar la compensacion. */
public class ControllableProvisioningPort extends DeclarativeProvisioningAdapter {
    public volatile boolean failBucket;

    @Override
    public String createBucket(UUID tenantId) {
        if (failBucket) {
            throw new ProvisioningException(Reason.INFRASTRUCTURE_ERROR, "bucket no disponible");
        }
        return super.createBucket(tenantId);
    }
}
