package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.review.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.tenant.context.TenantDataSourceRouter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** T-01: el contexto arranca con el DataSource enrutado por tenant y la migracion por silo disponible. */
@SpringBootTest
@Import(TestBeans.class)
class ReviewApplicationTest {

    @Autowired DataSource dataSource;
    @Autowired TenantSchemaMigrator migrator;

    @Test
    void contextLoads() throws Exception {
        assertThat(dataSource.unwrap(TenantDataSourceRouter.class)).isNotNull();
        assertThat(migrator).isNotNull();
    }
}
