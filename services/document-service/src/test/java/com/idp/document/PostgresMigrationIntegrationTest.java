package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** T-02: la migracion V1 aplica en PostgreSQL real y las unicidades de RN-01 / SEC-029 se cumplen. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static void insert(Connection c, String hash, String radicado, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into document (id, tenant_id, hash_sha256, typology, "
                + "radicado, version, status, classification, created_at, updated_at) "
                + "values (?, 't', ?, 'EC', ?, ?, 'RECIBIDO', 'CONFIDENCIAL', now(), now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, hash);
            ps.setString(3, radicado);
            ps.setInt(4, version);
            ps.executeUpdate();
        }
    }

    @Test
    void ac01_migraYGarantizaUnicidadPorHashYPorTuplaDeNegocio() throws SQLException {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            insert(c, "h1", "r1", 1);
            assertThatThrownBy(() -> insert(c, "h1", "r2", 1)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(c, "h2", "r1", 1)).isInstanceOf(SQLException.class);
            insert(c, "h3", "r1", 2);
            try (var rs = c.createStatement().executeQuery("select count(*) from outbox")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (var rs = c.createStatement().executeQuery("select count(*) from processed_event")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
