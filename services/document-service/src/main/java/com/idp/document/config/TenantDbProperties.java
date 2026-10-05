package com.idp.document.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Conexion al silo por tenant. {@code jdbcUrlTemplate} usa el marcador {tenant}. */
@ConfigurationProperties("idp.tenant-db")
public record TenantDbProperties(
        String jdbcUrlTemplate,
        String username,
        String password,
        @DefaultValue("50") int maxPools,
        @DefaultValue("5") int poolSize) {

    public static List<String> parseTenants(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split("\s*,\s*"));
    }
}
