package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KafkaSecurityEnvironmentPostProcessorTest {

    @Test
    void plaintextRejectedOutsideDevMode() {
        assertThrows(IllegalStateException.class,
            () -> KafkaSecurityEnvironmentPostProcessor.validate("kafka:9092", "PLAINTEXT", false));
        assertThrows(IllegalStateException.class,
            () -> KafkaSecurityEnvironmentPostProcessor.validate("kafka:9092", null, false));
    }

    @Test
    void sslAcceptedAndPlaintextAllowedInDevMode() {
        assertDoesNotThrow(() -> KafkaSecurityEnvironmentPostProcessor.validate("kafka:9092", "SSL", false));
        assertDoesNotThrow(() -> KafkaSecurityEnvironmentPostProcessor.validate("localhost:9092", "PLAINTEXT", true));
        assertDoesNotThrow(() -> KafkaSecurityEnvironmentPostProcessor.validate(null, null, false));
    }
}
