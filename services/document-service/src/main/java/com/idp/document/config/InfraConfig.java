package com.idp.document.config;

import com.idp.document.infra.HttpRendererClient;
import com.idp.document.infra.RendererClient;
import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.KeyService;
import com.idp.kms.OpenBaoTransitKeyService;
import com.idp.security.CachingRoleAssignmentVerifier;
import com.idp.security.JdbcRoleAssignmentSource;
import com.idp.security.RoleAssignmentSource;
import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.TenantAuthorizer;
import com.idp.storage.ObjectStore;
import com.idp.storage.S3Clients;
import com.idp.storage.S3ObjectStore;
import com.idp.tenant.context.JdbcTenantDirectory;
import com.idp.tenant.context.StaticTenantDirectory;
import com.idp.tenant.context.TenantDirectory;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
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

/** Adaptadores de proveedor (puertos ObjectStore, KeyService), autorizacion y cliente del renderer. */
@Configuration
public class InfraConfig {

    @Bean
    @ConditionalOnProperty("idp.storage.bucket")
    ObjectStore objectStore(@Value("${idp.storage.endpoint:}") String endpoint,
                            @Value("${idp.storage.region:us-east-1}") String region,
                            @Value("${idp.storage.access-key:}") String accessKey,
                            @Value("${idp.storage.secret-key:}") String secretKey,
                            @Value("${idp.storage.path-style:false}") boolean pathStyle,
                            @Value("${idp.storage.bucket}") String bucket) {
        return new S3ObjectStore(S3Clients.create(endpoint.isBlank() ? null : URI.create(endpoint), region,
                accessKey.isBlank() ? null : accessKey, secretKey, pathStyle), bucket);
    }

    @Bean
    @ConditionalOnProperty("idp.openbao.address")
    KeyService keyService(RestClient.Builder builder, @Value("${idp.openbao.address}") String address,
                          @Value("${idp.openbao.token}") String token,
                          @Value("${idp.openbao.transit-mount:transit}") String mount) {
        return new OpenBaoTransitKeyService(builder, address, () -> token, mount);
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

    /** Fallback de desarrollo: lista estatica {@code idp.tenants}. */
    @Bean
    @ConditionalOnExpression("'${idp.control-db.url:}'.isEmpty()")
    TenantDirectory staticTenantDirectory(@Value("${idp.tenants:}") String tenants) {
        return StaticTenantDirectory.fromCsv(tenants);
    }

    @Bean
    RoleAssignmentVerifier roleAssignmentVerifier(RoleAssignmentSource source) {
        return new CachingRoleAssignmentVerifier(source);
    }

    @Bean
    TenantAuthorizer tenantAuthorizer(RoleAssignmentVerifier verifier) {
        return new TenantAuthorizer(verifier);
    }

    @Bean
    RendererClient rendererClient(RendererProperties props, ObjectProvider<SslBundles> bundles) {
        SSLContext ssl = null;
        if (props.sslBundle() != null && !props.sslBundle().isBlank()) {
            ssl = bundles.getObject().getBundle(props.sslBundle()).createSslContext();
        }
        return new HttpRendererClient(props.url(), props.connectTimeout(), props.readTimeout(), ssl);
    }
}
