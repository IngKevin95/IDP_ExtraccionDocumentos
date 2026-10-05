package com.idp.extraction.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * H2 no soporta {@code insert ... on conflict (pk) do nothing} (usado por libs/events). Este DataSource de
 * pruebas lo reescribe a un {@code merge ... when not matched} equivalente; contra PostgreSQL real no se usa.
 */
final class H2CompatDataSource extends DelegatingDataSource {

    private static final Pattern ON_CONFLICT = Pattern.compile(
        "^\\s*insert into (\\w+) \\(([^)]*)\\) values \\(([^)]*)\\) on conflict \\((\\w+)\\) do nothing\\s*$",
        Pattern.CASE_INSENSITIVE);

    H2CompatDataSource(DataSource target) {
        super(target);
    }

    static String rewrite(String sql) {
        Matcher m = ON_CONFLICT.matcher(sql);
        if (!m.matches()) {
            return sql;
        }
        String table = m.group(1);
        String cols = m.group(2).trim();
        String pk = m.group(4);
        String sourceCols = String.join(", ", java.util.Arrays.stream(cols.split(",")).map(c -> "s." + c.trim()).toList());
        return "merge into " + table + " using (values (" + m.group(3) + ")) as s(" + cols + ") on " + table + "." + pk
            + " = s." + pk + " when not matched then insert (" + cols + ") values (" + sourceCols + ")";
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private static Connection wrap(Connection target) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if (args != null && args.length > 0 && args[0] instanceof String sql
                    && (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))) {
                    args[0] = rewrite(sql);
                }
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}
