package com.idp.audit.support;

import com.idp.kms.InMemoryKeyService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {
    @Bean
    @Primary
    InMemoryKeyService keyService() {
        return new InMemoryKeyService();
    }

    @Bean
    @Primary
    RecordingImmutableStore immutableStore() {
        return new RecordingImmutableStore();
    }

    @Bean
    @Primary
    CapturingPublisher publisher() {
        return new CapturingPublisher();
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
