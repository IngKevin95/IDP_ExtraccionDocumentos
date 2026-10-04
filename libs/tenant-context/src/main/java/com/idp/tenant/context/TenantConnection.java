package com.idp.tenant.context;

import java.util.Objects;

/** Datos de conexion JDBC de un silo de tenant. La credencial nunca aparece en toString. */
public record TenantConnection(String jdbcUrl, String username, String password) {

    public TenantConnection {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }

    @Override
    public String toString() {
        return "TenantConnection[jdbcUrl=" + jdbcUrl + ", username=" + username + ", password=***]";
    }
}
