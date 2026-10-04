package com.idp.tenant.config;

import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Respaldo cuando no hay adaptador KMS (OpenBao) configurado: toda operacion falla de forma explicita.
     * El adaptador real se registra como bean {@link KeyService} y reemplaza a este.
     */
    @Bean
    @ConditionalOnMissingBean(KeyService.class)
    KeyService unconfiguredKeyService() {
        return new KeyService() {
            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("KeyService (OpenBao) no configurado");
            }

            @Override
            public CryptoResult wrapDek(TenantId t, byte[] dek, String kekId, Map<String, String> aad) {
                throw unavailable();
            }

            @Override
            public CryptoResult unwrapDek(TenantId t, byte[] wrapped, String kekId, Map<String, String> aad) {
                throw unavailable();
            }

            @Override
            public CryptoResult sign(TenantId t, byte[] data, String keyId) {
                throw unavailable();
            }

            @Override
            public boolean verify(TenantId t, byte[] data, byte[] signature, String keyId) {
                throw unavailable();
            }

            @Override
            public void disableKek(TenantId t, String kekId) {
                throw unavailable();
            }
        };
    }
}
