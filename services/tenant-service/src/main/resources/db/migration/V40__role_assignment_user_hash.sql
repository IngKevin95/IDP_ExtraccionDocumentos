-- Migracion V40: Mitigacion de oraculo de usuarios con indice hash

ALTER TABLE role_assignment ADD COLUMN user_hash VARCHAR(64) DEFAULT '0000' NOT NULL;
ALTER TABLE role_assignment ALTER COLUMN user_hash DROP DEFAULT;

-- Eliminamos el indice sobre PII en texto claro
DROP INDEX IF EXISTS idx_role_assignment_lookup;

-- Creamos el indice sobre el hash
CREATE INDEX idx_role_assignment_hash ON role_assignment(tenant_id, user_hash);
