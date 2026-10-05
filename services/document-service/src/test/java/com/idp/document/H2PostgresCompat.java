package com.idp.document;

import com.idp.tenant.context.TenantDataSourceRouter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * H2 no soporta {@code ON CONFLICT DO NOTHING} (SQL que usan JdbcOutboxPublisher e IdempotentEventConsumer de
 * libs/events contra PostgreSQL). Solo en tests, el DataSource enrutado se envuelve y esa sentencia se reescribe
 * a un {@code INSERT ... SELECT ... WHERE NOT EXISTS} equivalente; los parametros no cambian de orden ni cantidad.
 * La semantica real contra PostgreSQL la cubren los tests con Testcontainers de libs/events.
 */
final class H2PostgresCompat {

    private static final Pattern ON_CONFLICT = Pattern.compile(
            "(?is)^\\s*insert\\s+into\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*values\\s*\\(([^)]*)\\)\\s*"
                    + "on\\s+conflict\\s*\\((\\w+)\\)\\s*do\\s+nothing\\s*$");

    private H2PostgresCompat() {
    }

    static BeanPostProcessor beanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof TenantDataSourceRouter router ? new CompatDataSource(router) : bean;
            }
        };
    }

    static String rewrite(String sql) {
        Matcher m = ON_CONFLICT.matcher(sql);
        if (!m.matches()) {
            return sql;
        }
        String table = m.group(1);
        String[] cols = m.group(2).trim().split("\\s*,\\s*");
        String key = m.group(4);
        List<String> casts = new ArrayList<>();
        for (String col : cols) {
            casts.add(col.endsWith("id") ? "cast(? as uuid)" : "cast(? as varchar)");
        }
        return "insert into " + table + " (" + String.join(", ", cols) + ") select * from (values ("
                + String.join(", ", casts) + ")) as v(" + String.join(", ", cols) + ") where not exists "
                + "(select 1 from " + table + " t0 where t0." + key + " = v." + key + ")";
    }

    private static final class CompatDataSource extends DelegatingDataSource {
        CompatDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        private static Connection wrap(Connection real) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                    args[0] = rewrite(sql);
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, handler);
        }
    }
}
