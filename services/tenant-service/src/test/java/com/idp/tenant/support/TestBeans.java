package com.idp.tenant.support;

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
    ControllableProvisioningPort provisioningPort() {
        return new ControllableProvisioningPort();
    }
}
