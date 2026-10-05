package com.idp.document.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("idp.document")
public record DocumentProperties(
        @DefaultValue("documents") String kekId,
        @DefaultValue("52428800") long maxFileBytes,
        @DefaultValue("http://localhost:8080") String publicBaseUrl,
        String downloadSecret,
        @DefaultValue("5m") Duration downloadTtl,
        @DefaultValue("false") boolean migrateOnStartup,
        @DefaultValue Relay relay) {

    public record Relay(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration interval) {
    }
}
