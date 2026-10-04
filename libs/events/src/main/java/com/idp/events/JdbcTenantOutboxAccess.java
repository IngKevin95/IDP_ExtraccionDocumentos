package com.idp.events;

import com.idp.tenant.context.TenantContextHolder;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementacion sobre un DataSource enrutado por tenant (TenantDataSourceRouter): fija el tenant
 * en el contexto, abre la transaccion y expone el repositorio del silo correspondiente.
 */
public final class JdbcTenantOutboxAccess implements TenantOutboxAccess {

    private final OutboxRepository repository;
    private final TransactionTemplate tx;

    public JdbcTenantOutboxAccess(DataSource routedDataSource) {
        this.repository = new OutboxRepository(new JdbcTemplate(routedDataSource));
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(routedDataSource));
    }

    @Override
    public <T> T inTransaction(String tenantId, Function<OutboxRepository, T> work) {
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            return tx.execute(status -> work.apply(repository));
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }
}
