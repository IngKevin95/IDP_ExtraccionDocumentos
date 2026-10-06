package com.idp.document.config;

import com.idp.tenant.context.OpenBaoTenantCredentialProvider;
import com.idp.tenant.context.TenantConnection;
import com.idp.tenant.context.TenantCredentialProvider;
import com.idp.tenant.context.TenantDataSourceRouter;
import com.idp.tenant.context.TenantDirectory;
import com.idp.tenant.context.TenantNotAvailableException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Persistencia por silo de tenant: el DataSource primario enruta cada conexion al silo del tenant del contexto
 * (TenantDataSourceRouter). Flyway no corre sobre el DataSource enrutado al arrancar: se aplica por tenant con
 * {@link TenantSchemaMigrator}.
 */
@Configuration
@EnableConfigurationProperties({DocumentProperties.class, RendererProperties.class, TenantDbProperties.class})
public class PersistenceConfig {

    private static final String TENANT_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /** Credenciales dinamicas via OpenBao (database secrets engine) cuando hay direccion configurada. */
    @Bean
    @ConditionalOnExpression("!'${idp.openbao.address:}'.isEmpty()")
    TenantCredentialProvider openBaoCredentialProvider(RestClient.Builder builder, TenantDbProperties db,
            @Value("${idp.openbao.address}") String address, @Value("${idp.openbao.token}") String token,
            @Value("${idp.openbao.creds-path:database/creds/tenant-{tenant}}") String credsPath,
            @Value("${idp.security.dev-mode:false}") boolean devMode,
            @Value("${idp.openbao.ssl-bundle:}") String sslBundleName,
            org.springframework.beans.factory.ObjectProvider<org.springframework.boot.ssl.SslBundles> bundles) {
        javax.net.ssl.SSLContext ssl = null;
        if (sslBundleName != null && !sslBundleName.isBlank()) {
            ssl = bundles.getObject().getBundle(sslBundleName).createSslContext();
        }
        return new OpenBaoTenantCredentialProvider(builder, address, () -> token, credsPath, db.jdbcUrlTemplate(), devMode, ssl);
    }

    /** Credenciales estaticas por configuracion (desarrollo y pruebas). */
    @Bean
    @ConditionalOnExpression("'${idp.openbao.address:}'.isEmpty()")
    TenantCredentialProvider staticCredentialProvider(TenantDbProperties db) {
        return tenantId -> {
            if (tenantId == null || !tenantId.matches(TENANT_PATTERN) || db.jdbcUrlTemplate() == null
                    || db.jdbcUrlTemplate().isBlank()) {
                throw new TenantNotAvailableException("Tenant sin conexion configurada");
            }
            return new TenantConnection(db.jdbcUrlTemplate().replace("{tenant}", tenantId),
                    db.username() == null ? "" : db.username(), db.password() == null ? "" : db.password());
        };
    }

    @Bean
    @Primary
    TenantDataSourceRouter tenantRoutingDataSource(TenantCredentialProvider credentials, TenantDbProperties db) {
        return new TenantDataSourceRouter(credentials, db.maxPools(), db.poolSize());
    }

    /** tenant.baja_iniciada / rotacion de credenciales desalojan el pool con drenado (H7). */
    @Bean
    com.idp.security.TenantPoolEvictionKafkaListener tenantPoolEvictionListener(TenantDataSourceRouter router,
                                                                              com.idp.events.EventSerde serde,
            com.idp.events.EventOriginGuard guard) {
        return new com.idp.security.TenantPoolEvictionKafkaListener(router, serde, guard);
    }

    @Bean
    TenantSchemaMigrator tenantSchemaMigrator(TenantCredentialProvider credentials) {
        return new TenantSchemaMigrator(credentials);
    }

    @Bean
    @ConditionalOnProperty("idp.document.migrate-on-startup")
    ApplicationRunner migrateTenantsOnStartup(TenantSchemaMigrator migrator, TenantDirectory tenants) {
        return args -> tenants.activeTenants().forEach(migrator::migrate);
    }

    /** Aplica db/migration al silo de un tenant (aprovisionamiento o arranque). */
    public static final class TenantSchemaMigrator {
        private final TenantCredentialProvider credentials;

        TenantSchemaMigrator(TenantCredentialProvider credentials) {
            this.credentials = credentials;
        }

        public void migrate(String tenantId) {
            TenantConnection c = credentials.resolve(tenantId);
            Flyway.configure().dataSource(c.jdbcUrl(), c.username(), c.password())
                    .locations("classpath:db/migration").load().migrate();
        }
    }
}
