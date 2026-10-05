package com.idp.tenant.context;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link LegalHoldGate} sobre {@code legal_hold_records} (base de control); sin cache para no omitir un hold nuevo. */
public final class JdbcLegalHoldGate implements LegalHoldGate {

    static final String DOC_SQL = "select count(*) from legal_hold_records where tenant_id = ? and status = 'ACTIVE' "
        + "and (document_id is null or document_id = ?)";
    static final String ANY_SQL = "select count(*) from legal_hold_records where tenant_id = ? and status = 'ACTIVE'";

    private final JdbcTemplate controlDb;

    public JdbcLegalHoldGate(JdbcTemplate controlDb) {
        this.controlDb = controlDb;
    }

    @Override
    public boolean isHeld(String tenantId, UUID documentId) {
        Integer n = controlDb.queryForObject(DOC_SQL, Integer.class, UUID.fromString(tenantId), documentId);
        return n != null && n > 0;
    }

    @Override
    public boolean anyHold(String tenantId) {
        Integer n = controlDb.queryForObject(ANY_SQL, Integer.class, UUID.fromString(tenantId));
        return n != null && n > 0;
    }
}
