package com.idp.chat.infra;

import com.idp.chat.service.Caller;
import com.idp.chat.service.DocumentAccessChecker;
import com.idp.chat.service.Exceptions.AccessDeniedException;
import com.idp.chat.service.Exceptions.DocumentNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lee la tabla {@code document} del document-service en el silo del tenant. Misma regla que
 * {@code Caller.canView} del document-service (SEC-018): un documento ALTAMENTE_CONFIDENCIAL solo lo ven su cargador
 * y los roles privilegiados. Un documento ausente, de otro tenant o purgado es "no encontrado".
 */
@Component
public class JdbcDocumentAccessChecker implements DocumentAccessChecker {

    private static final String SELECT = "select classification, uploaded_by from document "
            + "where id = ? and tenant_id = ? and purged_at is null";

    private final JdbcTemplate jdbc;

    public JdbcDocumentAccessChecker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(String classification, String uploadedBy) {
    }

    @Override
    public void assertCanRead(Caller caller, UUID documentId) {
        List<Row> rows = jdbc.query(SELECT, (rs, i) -> new Row(rs.getString("classification"),
                rs.getString("uploaded_by")), documentId, caller.tenantId());
        if (rows.isEmpty()) {
            throw new DocumentNotFoundException();
        }
        Row d = rows.get(0);
        boolean restricted = "ALTAMENTE_CONFIDENCIAL".equals(d.classification());
        if (restricted && !caller.privileged() && !caller.userId().equals(d.uploadedBy())) {
            throw new AccessDeniedException("Sin acceso al documento");
        }
    }

    @Override
    public boolean isIndexable(UUID documentId) {
        String tenant = com.idp.tenant.context.TenantContextHolder.getTenantId();
        if (tenant == null) {
            return false;
        }
        Integer n = jdbc.queryForObject("select count(*) from document where id = ? and tenant_id = ? "
                + "and purged_at is null", Integer.class, documentId, tenant);
        return n != null && n > 0;
    }
}
