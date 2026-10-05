package com.idp.tenant.context;

import java.util.List;

/** Lista fija de tenants: solo fallback de desarrollo y pruebas. */
public final class StaticTenantDirectory implements TenantDirectory {

    private final List<String> tenants;

    public StaticTenantDirectory(List<String> tenants) {
        this.tenants = List.copyOf(tenants);
    }

    /** Parsea una lista separada por comas (vacia = sin tenants). */
    public static StaticTenantDirectory fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return new StaticTenantDirectory(List.of());
        }
        return new StaticTenantDirectory(List.of(csv.trim().split("\s*,\s*")));
    }

    @Override
    public List<String> activeTenants() {
        return tenants;
    }
}
