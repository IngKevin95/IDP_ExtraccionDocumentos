package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.document.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.tenant.context.TenantDataSourceRouter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** El contexto arranca con el DataSource enrutado por tenant (sin excluir JDBC ni tenant-context). */
@SpringBootTest
@Import(TestBeans.class)
class DocumentApplicationTest {

    @Autowired DataSource dataSource;
    @Autowired TenantSchemaMigrator migrator;

    @Test
    void contextLoads() throws Exception {
        assertThat(dataSource.unwrap(TenantDataSourceRouter.class)).isNotNull();
        assertThat(migrator).isNotNull();
    }
}
