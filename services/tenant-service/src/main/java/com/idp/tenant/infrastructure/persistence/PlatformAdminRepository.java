package com.idp.tenant.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** platform_admin: lista de administradores de plataforma habilitados (A3). */
@Repository
public class PlatformAdminRepository {
    private final JdbcClient jdbc;

    public PlatformAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActive(String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        Integer n = jdbc.sql("SELECT COUNT(*) FROM platform_admin WHERE subject = :s AND disabled_at IS NULL")
                .param("s", subject).query(Integer.class).single();
        return n != null && n > 0;
    }
}
