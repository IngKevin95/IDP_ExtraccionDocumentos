package com.idp.chat.config;

import com.idp.kms.EnvelopeCrypto;
import com.idp.security.CachingRoleAssignmentVerifier;
import com.idp.security.JdbcRoleAssignmentSource;
import com.idp.security.RoleAssignmentSource;
import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.TenantAuthorizer;
import com.idp.storage.ObjectStore;
import com.idp.tenant.context.JdbcLegalHoldGate;
import com.idp.tenant.context.JdbcTenantDirectory;
import com.idp.tenant.context.LegalHoldGate;
import com.idp.tenant.context.TenantBucketResolver;
import com.idp.tenant.context.StaticTenantDirectory;
import com.idp.tenant.context.TenantDirectory;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolvers de silo, base de control y autorizacion por role_assignment (ObjectStore y KeyService: autoconfiguracion de libs). */
@Configuration
public class InfraConfig {

    /** Bucket por tenant desde silo_location (base de control). */
    @Bean
    @ConditionalOnProperty("idp.control-db.url")
    TenantBucketResolver jdbcTenantBucketResolver(@Qualifier("controlDataSource") HikariDataSource controlDataSource,
                                                  Clock clock, @Value("${idp.tenant-directory.ttl:30s}") Duration ttl) {
        return new TenantBucketResolver(new JdbcTemplate(controlDataSource), ttl, clock);
    }

    /** Fallback de desarrollo: bucket compartido {@code idp.storage.bucket}. */
    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    TenantBucketResolver fixedTenantBucketResolver(@Value("${idp.storage.bucket:}") String bucket) {
        return TenantBucketResolver.fixed(bucket);
    }

    /** Legal hold unificado: tabla legal_hold_records de la base de control. */
    @Bean
    @ConditionalOnProperty("idp.control-db.url")
    LegalHoldGate jdbcLegalHoldGate(@Qualifier("controlDataSource") HikariDataSource controlDataSource) {
        return new JdbcLegalHoldGate(new JdbcTemplate(controlDataSource));
    }

    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    LegalHoldGate noLegalHoldGate() {
        return LegalHoldGate.NONE;
    }

    /** Pool pequeno hacia la base de control de la plataforma (role_assignment, tenants). */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty("idp.control-db.url")
    HikariDataSource controlDataSource(@Value("${idp.control-db.url}") String url,
                                       @Value("${idp.control-db.username}") String user,
                                       @Value("${idp.control-db.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(5);
        return ds;
    }

    /** Fuente de role_assignment: base de control de la plataforma. */
    @Bean
    @ConditionalOnProperty("idp.control-db.url")
    RoleAssignmentSource roleAssignmentSource(@Qualifier("controlDataSource") HikariDataSource controlDataSource) {
        return new JdbcRoleAssignmentSource(new JdbcTemplate(controlDataSource));
    }

    /** Tenants ACTIVE desde la base de control (cache corta). */
    @Bean
    @ConditionalOnProperty("idp.control-db.url")
    TenantDirectory jdbcTenantDirectory(@Qualifier("controlDataSource") HikariDataSource controlDataSource,
                                        Clock clock, @Value("${idp.tenant-directory.ttl:30s}") Duration ttl) {
        return new JdbcTenantDirectory(new JdbcTemplate(controlDataSource), ttl, clock);
    }

    @Bean
    @ConditionalOnProperty("idp.control-db.url")
    com.idp.tenant.context.TenantKeyResolver tenantKeyResolver(@Qualifier("controlDataSource") HikariDataSource controlDataSource,
                                        Clock clock, @Value("${idp.tenant-directory.ttl:30s}") Duration ttl) {
        return new com.idp.tenant.context.TenantKeyResolver(new JdbcTemplate(controlDataSource), ttl, clock);
    }

    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    com.idp.tenant.context.TenantKeyResolver staticTenantKeyResolver(Clock clock, ChatProperties props) {
        return new com.idp.tenant.context.TenantKeyResolver(null, java.time.Duration.ZERO, clock) {
            @Override
            public com.idp.tenant.context.TenantKeyResolver.TenantKeys resolve(String tenantId) {
                return new com.idp.tenant.context.TenantKeyResolver.TenantKeys(props.kekId(), "audit-signing");
            }
        };
    }

    /** Fallback de desarrollo: lista estatica {@code idp.tenants}. */
    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    TenantDirectory staticTenantDirectory(@Value("${idp.tenants:}") String tenants) {
        return StaticTenantDirectory.fromCsv(tenants);
    }

    @Bean
    CachingRoleAssignmentVerifier roleAssignmentVerifier(RoleAssignmentSource source) {
        return new CachingRoleAssignmentVerifier(source);
    }

    /** acceso.revocado purga la cache de roles de este servicio (H10). */
    @Bean
    com.idp.security.AccesoRevocadoKafkaListener accesoRevocadoListener(CachingRoleAssignmentVerifier verifier,
                                                                         com.idp.events.EventSerde serde,
            com.idp.events.EventOriginGuard guard) {
        return new com.idp.security.AccesoRevocadoKafkaListener(verifier, serde, guard);
    }

    @Bean
    TenantAuthorizer tenantAuthorizer(RoleAssignmentVerifier verifier) {
        return new TenantAuthorizer(verifier);
    }

    /** Capa de texto nativa cifrada del bucket del tenant (la escribe el document-service). */
    @Bean
    com.idp.chat.infra.TextLayerSource textLayerSource(ObjectStore objects, EnvelopeCrypto crypto,
                                                     com.idp.tenant.context.TenantKeyResolver keys, ChatProperties props) {
        return new com.idp.chat.infra.ArtifactTextLayerSource(objects, crypto, keys, props.maxTextLayerBytes());
    }
}
