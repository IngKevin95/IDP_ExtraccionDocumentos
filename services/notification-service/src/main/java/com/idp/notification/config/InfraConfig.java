package com.idp.notification.config;

import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.KeyService;
import com.idp.kms.OpenBaoTransitKeyService;
import com.idp.security.CachingRoleAssignmentVerifier;
import com.idp.security.JdbcRoleAssignmentSource;
import com.idp.security.RoleAssignmentSource;
import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.TenantAuthorizer;
import com.idp.tenant.context.JdbcTenantDirectory;
import com.idp.tenant.context.StaticTenantDirectory;
import com.idp.tenant.context.TenantDirectory;
import com.idp.tenant.context.TenantKeyResolver;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Adaptadores de proveedor (KeyService), base de control, autorizacion y directorio de tenants. */
@Configuration
public class InfraConfig {

    @Bean
    @ConditionalOnExpression("!'${idp.openbao.address:}'.isEmpty()")
    KeyService keyService(RestClient.Builder builder, @Value("${idp.openbao.address}") String address,
                          @Value("${idp.openbao.token}") String token,
                          @Value("${idp.openbao.transit-mount:transit}") String mount,
                          @Value("${idp.security.dev-mode:false}") boolean devMode,
                          @Value("${idp.openbao.ssl-bundle:}") String sslBundleName,
                          ObjectProvider<SslBundles> bundles) {
        SSLContext ssl = null;
        if (sslBundleName != null && !sslBundleName.isBlank()) {
            ssl = bundles.getObject().getBundle(sslBundleName).createSslContext();
        }
        return new OpenBaoTransitKeyService(builder, address, () -> token, mount, devMode, ssl);
    }

    @Bean
    EnvelopeCrypto envelopeCrypto(KeyService keys) {
        return new EnvelopeCrypto(keys);
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
    TenantKeyResolver tenantKeyResolver(@Qualifier("controlDataSource") HikariDataSource controlDataSource,
                                        Clock clock, @Value("${idp.tenant-directory.ttl:30s}") Duration ttl) {
        return new TenantKeyResolver(new JdbcTemplate(controlDataSource), ttl, clock);
    }

    /** Fallback de desarrollo: KEK de datos fija configurada. */
    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    TenantKeyResolver staticTenantKeyResolver(Clock clock, @Value("${idp.notification.kek-id:webhooks}") String kekId) {
        return new TenantKeyResolver(null, Duration.ZERO, clock) {
            @Override
            public TenantKeys resolve(String tenantId) {
                return new TenantKeys(kekId, "audit-signing");
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

    /** acceso.revocado purga la cache de roles de este servicio. */
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
}
