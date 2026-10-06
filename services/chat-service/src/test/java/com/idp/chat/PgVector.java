package com.idp.chat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL con pgvector compartido por todas las pruebas (un contenedor; una base por tenant simula el silo). */
final class PgVector {

    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        PG.start();
    }

    private PgVector() {
    }

    /** Plantilla JDBC con el marcador {tenant} (una base db_{tenant} por tenant). */
    static String urlTemplate() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/db_{tenant}";
    }

    static void createDatabase(String tenant) {
        try (Connection c = PG.createConnection(""); Statement s = c.createStatement()) {
            s.execute("create database \"db_" + tenant + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
