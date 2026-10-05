-- A3: administradores de plataforma revalidados contra la base de control (el claim del token no basta).
CREATE TABLE platform_admin (
    subject VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP WITH TIME ZONE
);

-- A3/A4: solicitud y aprobacion por una segunda persona distinta (baja de tenant, liberacion de legal hold,
-- roles sensibles de tenant). target identifica el objeto de la accion (tenant, o usuario|rol).
CREATE TABLE approval_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    action VARCHAR(50) NOT NULL,
    target VARCHAR(500) NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by VARCHAR(255),
    approved_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL
);
CREATE INDEX idx_approval_request_lookup ON approval_request(tenant_id, action, target, status);
