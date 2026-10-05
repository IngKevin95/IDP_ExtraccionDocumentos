package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.notification.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.notification.http.ApacheWebhookTransport;
import com.idp.notification.net.WebhookUrlPolicy;
import com.idp.notification.support.TestBeans;
import com.idp.tenant.context.TenantDataSourceRouter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** T-01: el contexto arranca con el DataSource enrutado por tenant y el cableado del despacho saliente. */
@SpringBootTest
@Import(TestBeans.class)
class NotificationApplicationTest {

    @Autowired DataSource dataSource;
    @Autowired TenantSchemaMigrator migrator;
    @Autowired WebhookUrlPolicy urlPolicy;
    @Autowired ApacheWebhookTransport transport;

    @Test
    void contextLoads() throws Exception {
        assertThat(dataSource.unwrap(TenantDataSourceRouter.class)).isNotNull();
        assertThat(migrator).isNotNull();
        assertThat(urlPolicy).isNotNull();
        assertThat(transport).isNotNull();
    }
}
