package com.idp.events;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Falla el arranque si Kafka queda en PLAINTEXT fuera de dev-mode (H1). Produccion usa SSL (mTLS con KafkaUser
 * de Strimzi); PLAINTEXT solo se acepta con {@code idp.security.dev-mode=true}.
 */
public final class KafkaSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        validate(env.getProperty("spring.kafka.bootstrap-servers"), env.getProperty("spring.kafka.security.protocol"),
            env.getProperty("idp.security.dev-mode", Boolean.class, false));
    }

    static void validate(String bootstrap, String protocol, boolean devMode) {
        if (bootstrap == null || bootstrap.isBlank() || devMode) {
            return;
        }
        if (protocol == null || protocol.isBlank() || "PLAINTEXT".equalsIgnoreCase(protocol)
            || "SASL_PLAINTEXT".equalsIgnoreCase(protocol)) {
            throw new IllegalStateException(
                "spring.kafka.security.protocol debe ser SSL o SASL_SSL salvo idp.security.dev-mode=true");
        }
    }
}
