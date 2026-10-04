package com.idp.tenant.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Utilidades JDBC portables (PostgreSQL y H2 en modo PostgreSQL). */
final class Db {
    private Db() {}

    static OffsetDateTime odt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    static UUID uuid(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }
}
