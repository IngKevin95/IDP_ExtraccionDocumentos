package com.idp.review.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parametros del review-service. {@code criticalFields} son fragmentos de nombre de campo: un campo es critico si su
 * nombre normalizado contiene alguno (sesgo conservador hacia exigir cuatro ojos).
 */
@ConfigurationProperties("idp.review")
public record ReviewProperties(
        @DefaultValue("documents") String kekId,
        @DefaultValue("http://localhost:8080") String publicBaseUrl,
        String cropSecret,
        @DefaultValue("60s") Duration cropTtl,
        @DefaultValue("4h") Duration sla,
        @DefaultValue("1h") Duration escalationInterval,
        @DefaultValue("3") int maxEscalationLevel,
        @DefaultValue({"monto", "identificacion", "cuenta", "producto", "tipo_medida", "radicado"})
        List<String> criticalFields,
        @DefaultValue("extraction-db") String fieldSource,
        @DefaultValue("false") boolean migrateOnStartup,
        @DefaultValue("20971520") int maxPageBytes,
        @DefaultValue Relay relay,
        @DefaultValue Escalation escalation) {

    public record Relay(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration interval) {
    }

    public record Escalation(@DefaultValue("true") boolean enabled, @DefaultValue("1m") Duration interval) {
    }
}
