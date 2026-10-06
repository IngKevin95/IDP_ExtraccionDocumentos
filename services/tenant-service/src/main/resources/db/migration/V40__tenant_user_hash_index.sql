-- Migracion V40: Mitigacion de oraculo de usuarios con indice hash
ALTER TABLE tenant_user ADD COLUMN email_hash VARCHAR(255);
UPDATE tenant_user SET email_hash = ENCODE(DIGEST(email, 'sha256'), 'hex');
ALTER TABLE tenant_user ALTER COLUMN email_hash SET NOT NULL;
CREATE UNIQUE INDEX idx_tenant_user_email_hash ON tenant_user(email_hash);
