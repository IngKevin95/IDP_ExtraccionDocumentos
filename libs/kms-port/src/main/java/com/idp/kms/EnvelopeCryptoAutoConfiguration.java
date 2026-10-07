package com.idp.kms;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** {@link EnvelopeCrypto} sobre el {@link KeyService} que haya aportado la aplicacion o {@link KmsAutoConfiguration}. */
@AutoConfiguration(after = KmsAutoConfiguration.class)
public class EnvelopeCryptoAutoConfiguration {

    @Bean
    @ConditionalOnBean(KeyService.class)
    @ConditionalOnMissingBean
    EnvelopeCrypto envelopeCrypto(KeyService keys) {
        return new EnvelopeCrypto(keys);
    }
}
