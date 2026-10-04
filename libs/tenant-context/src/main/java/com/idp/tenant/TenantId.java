package com.idp.tenant;

public record TenantId(String value) {
    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tenant ID no puede ser nulo o vacío");
        }
    }
}
