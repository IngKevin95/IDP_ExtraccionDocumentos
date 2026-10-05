package com.idp.tenant.context;

import java.util.List;

/** Directorio de tenants activos con silo aprovisionado (relay del outbox, migraciones por tenant). */
public interface TenantDirectory {

    /** Ids de tenant ACTIVE con silo; nunca nulo. */
    List<String> activeTenants();
}
