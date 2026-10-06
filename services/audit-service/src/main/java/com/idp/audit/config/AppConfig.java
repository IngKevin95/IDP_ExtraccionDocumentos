package com.idp.audit.config;

import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.kms.KeyService;
import com.idp.kms.OpenBaoTransitKeyService;
import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.storage.S3Clients;
import com.idp.storage.S3ImmutableStore;
import com.idp.tenant.TenantId;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty("idp.control-db.url")
    com.idp.tenant.context.TenantKeyResolver tenantKeyResolver(JdbcTemplate controlDb, Clock clock,
                                                               @Value("${idp.tenant-directory.ttl:30s}") Duration ttl) {
        return new com.idp.tenant.context.TenantKeyResolver(controlDb, ttl, clock);
    }

    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    com.idp.tenant.context.TenantKeyResolver staticTenantKeyResolver(Clock clock, @Value("${idp.audit.signing-key-id:audit-signing}") String signingKeyId) {
        return new com.idp.tenant.context.TenantKeyResolver(null, Duration.ZERO, clock) {
            @Override
            public TenantKeys resolve(String tenantId) {
                return new TenantKeys("documents", signingKeyId);
            }
        };
    }

    @Bean
    EventSerde eventSerde() {
        return new EventSerde();
    }

    @Bean
    com.idp.events.EventOriginGuard eventOriginGuard(
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        return new com.idp.events.EventOriginGuard(com.idp.events.EventTopology.defaults(),
            registry.getIfAvailable());
    }

    @Bean
    EventSchemaValidator eventSchemaValidator(EventSerde serde) {
        return new EventSchemaValidator(serde);
    }

    @Bean
    IdempotentEventConsumer idempotentEventConsumer(JdbcTemplate jdbc, TransactionTemplate tx,
                                                    EventSchemaValidator validator, EventSerde serde) {
        return new IdempotentEventConsumer(jdbc, tx, validator, serde);
    }

    /** Adaptador OpenBao Transit (ed25519 de auditoria, separado de las KEK) cuando hay direccion configurada. */
    @Bean
    @ConditionalOnExpression("!'${idp.audit.kms.openbao.address:}'.isEmpty()")
    KeyService openBaoKeyService(RestClient.Builder builder,
                                 @Value("${idp.audit.kms.openbao.address}") String address,
                                 @Value("${idp.audit.kms.openbao.token:}") String token,
                                 @Value("${idp.audit.kms.openbao.transit-mount:transit}") String mount,
                                 @Value("${idp.security.dev-mode:false}") boolean devMode,
                                 @Value("${idp.audit.kms.openbao.ssl-bundle:}") String sslBundleName,
                                 org.springframework.beans.factory.ObjectProvider<org.springframework.boot.ssl.SslBundles> bundles) {
        javax.net.ssl.SSLContext ssl = null;
        if (sslBundleName != null && !sslBundleName.isBlank()) {
            ssl = bundles.getObject().getBundle(sslBundleName).createSslContext();
        }
        return new OpenBaoTransitKeyService(builder, address, () -> token, mount, devMode, ssl);
    }

    /** Respaldo sin KMS configurado: toda operacion falla de forma explicita (nunca firma en local). */
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

    /** Bucket WORM (S3 Object Lock, modo COMPLIANCE) cuando hay bucket configurado. */
    @Bean
    @ConditionalOnProperty(name = "idp.audit.worm.bucket")
    ImmutableStore s3ImmutableStore(@Value("${idp.audit.worm.bucket}") String bucket,
                                    @Value("${idp.audit.worm.endpoint:}") String endpoint,
                                    @Value("${idp.audit.worm.region:us-east-1}") String region,
                                    @Value("${idp.audit.worm.access-key:}") String accessKey,
                                    @Value("${idp.audit.worm.secret-key:}") String secretKey,
                                    @Value("${idp.audit.worm.path-style:true}") boolean pathStyle) {
        return new S3ImmutableStore(S3Clients.create(endpoint.isBlank() ? null : URI.create(endpoint), region,
                accessKey.isBlank() ? null : accessKey, secretKey, pathStyle), bucket);
    }

    /** Respaldo sin bucket WORM configurado: el anclaje falla de forma explicita (no hay anclaje silencioso). */
    @Bean
    @ConditionalOnMissingBean(ImmutableStore.class)
    ImmutableStore unconfiguredImmutableStore() {
        return new ImmutableStore() {
            private ObjectStore.StorageException unavailable() {
                return new ObjectStore.StorageException("ImmutableStore (WORM) no configurado");
            }

            @Override
            public void putWithRetention(TenantId t, String path, InputStream data, ObjectMetadata m, Duration r) {
                throw unavailable();
            }

            @Override
            public void applyLegalHold(TenantId t, String path) {
                throw unavailable();
            }

            @Override
            public void removeLegalHold(TenantId t, String path) {
                throw unavailable();
            }

            @Override
            public void put(TenantId t, String path, InputStream data, ObjectMetadata m) {
                throw unavailable();
            }

            @Override
            public InputStream get(TenantId t, String path) {
                throw unavailable();
            }

            @Override
            public void delete(TenantId t, String path) {
                throw unavailable();
            }
        };
    }
}
