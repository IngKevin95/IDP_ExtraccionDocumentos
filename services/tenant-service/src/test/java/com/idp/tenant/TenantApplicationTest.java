package com.idp.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idp.tenant.support.TestBeans;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T-01: el contexto arranca y Flyway aplica las migraciones (planes base sembrados). */
@SpringBootTest
@Import(TestBeans.class)
class TenantApplicationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void contextLoadsAndMigrationsSeedPlans() {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM planes").query(Integer.class).single();
        assertEquals(2, n);
    }
}
