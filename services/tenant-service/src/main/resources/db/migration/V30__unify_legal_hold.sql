-- K2: fuente unica de legal hold = legal_hold_records (compartida con audit-service en la base de control).
CREATE TABLE IF NOT EXISTS legal_hold_records (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    document_id UUID,
    reason      TEXT         NOT NULL,
    applied_by  VARCHAR(100) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    released_by VARCHAR(100),
    released_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_legal_hold_tenant_doc ON legal_hold_records (tenant_id, document_id);

INSERT INTO legal_hold_records (id, tenant_id, document_id, reason, applied_by, status, created_at, released_by, released_at)
SELECT id, tenant_id, NULL, reason_code, applied_by,
       CASE WHEN released_at IS NULL THEN 'ACTIVE' ELSE 'RELEASED' END, applied_at, released_by, released_at
FROM legal_hold;

DROP TABLE legal_hold;
ALTER TABLE tenant_config DROP COLUMN legal_hold;
