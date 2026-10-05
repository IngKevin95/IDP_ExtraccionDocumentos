-- Solo tests: role_assignment pertenece a tenant-service en la base de control compartida.
create table role_assignment (
    id uuid primary key,
    tenant_id uuid not null,
    user_id varchar(255) not null,
    role varchar(50) not null,
    expires_at timestamp with time zone,
    deleted_at timestamp with time zone
);
