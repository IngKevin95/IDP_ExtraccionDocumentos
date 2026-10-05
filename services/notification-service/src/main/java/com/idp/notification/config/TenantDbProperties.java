package com.idp.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("idp.tenant-db")
public record TenantDbProperties(
        String jdbcUrlTemplate,
        String username,
        String password,
        @DefaultValue("50") int maxPools,
        @DefaultValue("5") int poolSize) {
}